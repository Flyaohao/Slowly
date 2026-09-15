import logging
import queue
import threading

from sqlalchemy.orm import Session
from typing import Any, Dict, Iterator, List, Optional, Tuple

from app.core.database import SessionLocal
from app.repositories import (
    ai_repo,
    profile_repo,
    couple_repo,
    safety_repo,
)
from app.services.prompt_builder import build_prompt, build_stream_prompt
from app.services.scene_router import get_scene_config
from app.services.safety_service import (
    check_input_safety,
    check_output_safety,
    check_input_safety_detail,
    check_output_safety_detail,
    get_safety_response,
    merge_risk_levels,
)
from app.services.rag_service import retrieve_chunks, build_rag_context
from app.services.memory_service import get_memory_context, distill_in_background
from app.services.llm_client import llm, LlmError

logger = logging.getLogger("couple.ai")


def _preprocess(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
) -> Dict[str, Any]:
    """聊天链路共享前处理（13 步里的 1~9 步）。

    `chat()`（阻塞）与 `prepare_chat()`（流式）共用这一份实现，避免两条路径各写一遍。
    返回值中 `blocked` 非空表示输入被安全护栏拦截，此时不创建会话、不调用模型。

    注意：返回的 `session` 是 ORM 实例，只在请求级 `db` 存活期间可用，
    流式端点不要持有它（见 `prepare_chat`）。
    """
    scene = ai_repo.get_scene_by_key(db, scene_key)
    if not scene:
        raise ValueError("50001")

    input_risk, input_hits = check_input_safety_detail(user_input)
    if input_risk != "normal":
        # 审计旁路：自带会话，失败不影响拦截响应
        safety_repo.log_event(user_id, scene_key, "input", input_risk, input_hits)
        return {
            "blocked": get_safety_response(input_risk),
            "risk_level": input_risk,
            "session_id": session_id or 0,
            "scene": scene,
        }

    user_profile = profile_repo.get_latest_profile(db, user_id)
    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None

    user_scores: Dict[str, float] = {}
    partner_scores: Dict[str, float] = {}
    conflict_pattern = None

    if user_profile:
        dims = profile_repo.get_dimension_scores(db, user_profile.id)
        user_scores = {d.dimension_key: d.score for d in dims}

    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}

    couple_prof = profile_repo.get_latest_couple_profile(db, relation_id)
    if couple_prof:
        conflict_pattern = couple_prof.conflict_pattern

    if not session_id:
        scene_config = get_scene_config(scene_key)
        privacy = scene_config.get("privacy_level", "private")
        session = ai_repo.create_session(
            db, user_id, relation_id, scene_key, title=scene.name, privacy_level=privacy
        )
        session_id = session.id
    else:
        session = ai_repo.get_session_by_id(db, session_id)
        if not session or session.user_id != user_id:
            raise ValueError("50002")

    history = ai_repo.get_messages_by_session(db, session_id)
    recent_history = history[-20:] if len(history) > 20 else history
    history_text = "\n".join(
        f"{m.role}: {m.content}" for m in recent_history
    )

    user_profile_text = _format_profile(user_profile, user_scores)
    partner_profile_text = _format_profile(partner_profile, partner_scores)

    rag_chunks = retrieve_chunks(db, user_input)
    rag_context = build_rag_context(rag_chunks)

    memory_context = get_memory_context(db, user_id, relation_id)

    prompt_args = dict(
        scene_key=scene_key,
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict_pattern or "未确定",
        user_input=user_input,
        history=history_text,
        rag_context=rag_context,
        memory_context=memory_context,
    )
    # 同一份上下文，两种出口：结构化 JSON 版 / 自然语言流式版
    prompt = build_prompt(**prompt_args)
    stream_prompt = build_stream_prompt(**prompt_args)

    ai_repo.create_message(db, session_id, "user", user_input)
    ai_repo.save_prompt_version(db, user_id, relation_id, scene_key, prompt)

    # 必须在此提交，不能留到调用方：
    # 流式端点会先带着这份数据返回 StreamingResponse，请求级 db 随即被销毁，
    # 未提交的会话行与用户消息会被回滚，导致随后独立会话落库助手消息时外键失败。
    db.commit()

    return {
        "blocked": None,
        "session": session,
        "session_id": session_id,
        "scene": scene,
        "prompt": prompt,
        "stream_prompt": stream_prompt,
        "user_input": user_input,
        "rag_chunks": rag_chunks,
    }


def chat(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
) -> dict:
    ctx = _preprocess(db, user_id, relation_id, session_id, scene_key, user_input)

    if ctx["blocked"]:
        return {
            "session_id": ctx["session_id"],
            "message": {
                "id": 0,
                "role": "assistant",
                "content": ctx["blocked"],
                "structured_output": {"risk_level": ctx["risk_level"]},
                "risk_level": ctx["risk_level"],
                "created_at": None,
            },
            "blocked": True,
        }

    scene = ctx["scene"]
    session = ctx["session"]
    session_id = ctx["session_id"]

    ai_response = _call_llm(ctx["prompt"], scene_key)

    # `_call_llm` 已经过 `llm.invoke_structured()` 的 Pydantic 强校验
    # （字段名、类型、枚举都在那里定死），因此这里直接落库即可。
    # 此前还额外过了一道 `output_validator.validate_structured_output` 的手写白名单，
    # 它不做任何校验、只按另一份 schema 裁剪字段，导致 model 真实产出的
    # theory_refs / scene / scene_key 被静默丢弃——两份 schema 并存且互相矛盾。
    structured = ai_response

    output_risk, output_hits = check_output_safety_detail(str(ai_response))
    if output_risk != "normal":
        safety_repo.log_event(user_id, scene_key, "output", output_risk, output_hits)
        structured["risk_level"] = output_risk
        safety_resp = get_safety_response(output_risk)
        structured["safety_notice"] = safety_resp

    risk_level = structured.get("risk_level", "normal")

    assistant_msg = ai_repo.create_message(
        db,
        session_id,
        "assistant",
        ai_response.get("raw_text", ""),
        structured_output=structured,
        risk_level=risk_level,
    )

    session.title = session.title or scene.name
    db.commit()

    # 记忆沉淀：由模型判断本轮是否含值得长期记住的信息，写进 AiMemory。
    # 放后台线程 + 独立会话，既不占用本次响应时间，也不受请求级会话销毁影响。
    distill_in_background(
        user_id, relation_id, scene_key, ctx["user_input"], assistant_msg.content
    )

    return {
        "session_id": session_id,
        "message": {
            "id": assistant_msg.id,
            "role": "assistant",
            "content": assistant_msg.content,
            "structured_output": structured,
            "risk_level": risk_level,
            "created_at": assistant_msg.created_at,
        },
    }


def prepare_chat(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
) -> dict:
    """SSE 端点专用：只做前处理，返回**纯数据**（不含 ORM 对象）。

    为什么不能直接把 `_preprocess` 的结果交给生成器：
    `StreamingResponse` 的响应体是在请求级 `db` 会话之后才被消费的，
    此时 `ctx["session"]` 已经 Detached，再访问属性会抛异常。
    所以这里立刻把需要的信息拍平成基本类型，生成器只认这些数据。
    """
    ctx = _preprocess(db, user_id, relation_id, session_id, scene_key, user_input)

    if ctx["blocked"]:
        return {
            "blocked": True,
            "content": ctx["blocked"],
            "risk_level": ctx["risk_level"],
            "session_id": ctx["session_id"],
            "scene_key": scene_key,
        }

    return {
        "blocked": False,
        "session_id": ctx["session_id"],
        "scene_key": scene_key,
        "scene_name": ctx["scene"].name,
        "stream_prompt": ctx["stream_prompt"],
        "rag_hit": len(ctx["rag_chunks"]),
        # 记忆沉淀需要在响应体阶段（请求级 db 已销毁）用独立会话写库，
        # 故这里把落库所需的三个基本类型一并拍平带过去。
        "user_id": user_id,
        "relation_id": relation_id,
        "user_input": user_input,
    }


#: 流式心跳间隔（秒）。取值要明显小于客户端 readTimeout 与 Nginx proxy_read_timeout
_HEARTBEAT_INTERVAL = 8.0


def _stream_with_heartbeat(
    prompt: str, scene_key: str, interval: float = _HEARTBEAT_INTERVAL
) -> Iterator[Optional[Tuple[str, str]]]:
    """产出 `(kind, text)` 增量，静默期产出 `None` 作为心跳信号。

    `kind` 为 `llm.KIND_THINKING`（模型推理过程）或 `llm.KIND_CONTENT`（正文）。

    **为什么需要心跳**：推理模型的思考期不产出正文，而 SSE 连接恰恰在静默期
    最脆弱——客户端 okhttp readTimeout 与 Nginx proxy_read_timeout 都会把
    「长时间无数据」判定为断连。

    2026-09-14 起思考增量本身也会下推（`event: thinking`），首帧实测约 0.5s，
    静默期从近 20s 缩短到毫秒级；心跳遂退化为**兜底**：模型连推理都不吐
    （例如纯文本模型或网络卡顿）时，仍保证连接有字节流动。

    做法：把真正的调用丢到后台线程，主生成器只在队列上等待；超时即产出 `None`，
    由调用方翻译成 SSE 注释帧。这样连接始终有字节流动，而协议语义不变。
    """
    q: "queue.Queue[Any]" = queue.Queue()
    _END = object()

    def _worker() -> None:
        try:
            for item in llm.stream_events(
                [{"role": "system", "content": prompt}],
                temperature=0.7,
                max_tokens=1200,
                scene=scene_key,
            ):
                q.put(item)
        except BaseException as exc:  # noqa: BLE001 —— 原样抛回主线程处理
            q.put(exc)
        finally:
            q.put(_END)

    threading.Thread(target=_worker, daemon=True, name="llm-stream").start()

    while True:
        try:
            item = q.get(timeout=interval)
        except queue.Empty:
            yield None
            continue
        if item is _END:
            return
        if isinstance(item, BaseException):
            raise item
        yield item


def stream_chat_events(prepared: dict) -> Iterator[Dict[str, Any]]:
    """把一次聊天拆成 SSE 事件序列：meta → thinking* / delta* → done / error。

    事件协议（前端按 `event:` 名分发）：
    - `meta`     首帧，携带 session_id / scene_key / RAG 命中数，客户端据此绑定会话
    - `thinking` 推理模型的思考过程增量。**不是给用户当正文读的**，而是让前端
                 渲染一个「正在深度思考」的可展开面板——推理模型正文首字实测
                 要 18s 以上，而思考增量首个约 0.5s，这是消除空屏的关键通道。
                 非推理模型不产生该事件，前端需容忍它完全缺席。
    - `delta`    正文文本增量，逐块追加即得打字机效果
    - `done`     终帧，携带 message_id 与最终风险等级，客户端据此刷新消息列表
    - `error`    模型侧失败，客户端应提示重试

    另有 `comment` 类型的心跳帧（编码为 SSE 注释行 `: keep-alive`），
    仅用于保活，客户端解析器会忽略，不属于上述事件协议。

    生成器全程自行开闭 DB 会话（见 `_persist_streamed_message`），
    不持有请求级 session，因此可以安全地在响应体阶段运行。
    """
    yield {
        "event": "meta",
        "data": {
            "session_id": prepared["session_id"],
            "scene_key": prepared["scene_key"],
            "rag_hit": prepared.get("rag_hit", 0),
        },
    }

    if prepared["blocked"]:
        # 安全护栏命中：不调用模型，但保持同一套事件协议，前端无需特判
        yield {"event": "delta", "data": {"content": prepared["content"]}}
        yield {
            "event": "done",
            "data": {
                "session_id": prepared["session_id"],
                "message_id": 0,
                "risk_level": prepared["risk_level"],
                "blocked": True,
                "content": prepared["content"],
            },
        }
        return

    buffer: List[str] = []
    thinking_buffer: List[str] = []
    try:
        for item in _stream_with_heartbeat(prepared["stream_prompt"], prepared["scene_key"]):
            if item is None:
                # 兜底保活：模型连推理都不吐时，用注释帧证明连接还活着
                yield {"comment": "keep-alive"}
                continue

            kind, text = item
            if kind == llm.KIND_THINKING:
                thinking_buffer.append(text)
                yield {"event": "thinking", "data": {"content": text}}
                continue

            buffer.append(text)
            yield {"event": "delta", "data": {"content": text}}
    except LlmError as exc:
        logger.error("[AI] 流式调用失败 scene=%s: %s", prepared["scene_key"], exc)
        yield {
            "event": "error",
            "data": {"code": 50000, "message": "AI 服务异常，请稍后重试"},
        }
        return

    full_text = "".join(buffer).strip()
    if not full_text:
        yield {
            "event": "error",
            "data": {"code": 50000, "message": "AI 未返回内容，请重试"},
        }
        return

    output_risk, output_hits = check_output_safety_detail(full_text)
    if output_risk != "normal":
        safety_repo.log_event(
            prepared.get("user_id"), prepared["scene_key"], "output", output_risk, output_hits
        )
    structured: Dict[str, Any] = {
        "raw_text": full_text,
        "streamed": True,
        "risk_level": output_risk,
    }
    thinking = _trim_thinking("".join(thinking_buffer))
    if thinking:
        structured["thinking"] = thinking
    if output_risk != "normal":
        structured["safety_notice"] = get_safety_response(output_risk)

    message_id = _persist_streamed_message(
        session_id=prepared["session_id"],
        content=full_text,
        structured=structured,
        risk_level=output_risk,
        user_id=prepared.get("user_id"),
        relation_id=prepared.get("relation_id"),
        scene_key=prepared["scene_key"],
        user_input=prepared.get("user_input", ""),
    )

    yield {
        "event": "done",
        "data": {
            "session_id": prepared["session_id"],
            "message_id": message_id,
            "risk_level": output_risk,
            "blocked": False,
            "content": full_text,
            "finish_reason": "stop",
        },
    }


#: 落库的思考过程上限（字符）。推理模型的思考常为正文字数的 5~10 倍，
#: 无上限会明显撑大 ai_chat_message.structured_output。
_THINKING_MAX_CHARS = 4000


def _trim_thinking(text: str) -> str:
    """裁剪思考过程用于落库，超长时保留开头并标注被截断。

    保留开头而非结尾：思考的开头是"怎么理解这个问题"，信息密度高于末尾的收尾复述。
    """
    text = text.strip()
    if len(text) <= _THINKING_MAX_CHARS:
        return text
    return text[:_THINKING_MAX_CHARS] + "\n…（思考过程过长，已截断）"


def _persist_streamed_message(
    session_id: int,
    content: str,
    structured: dict,
    risk_level: str,
    user_id: Optional[int] = None,
    relation_id: Optional[int] = None,
    scene_key: str = "unknown",
    user_input: str = "",
) -> int:
    """流式结束后落库。

    用独立会话，且把异常吞掉只记日志：内容此刻已经推给用户了，
    落库失败不应该反过来影响这次回答的观感。返回 0 表示落库失败。
    """
    db = SessionLocal()
    try:
        msg = ai_repo.create_message(
            db,
            session_id,
            "assistant",
            content,
            structured_output=structured,
            risk_level=risk_level,
        )
        db.commit()
        # 记忆沉淀同样走后台线程 + 独立会话：本函数所在阶段请求级 db 已经销毁，
        # 不能复用这里的 db 之外的任何会话。
        if user_id and relation_id:
            distill_in_background(
                user_id, relation_id, scene_key, user_input, content
            )
        return msg.id
    except Exception:
        db.rollback()
        logger.exception("[AI] 流式消息落库失败 session=%s", session_id)
        return 0
    finally:
        db.close()


def rewrite_expression(
    db: Session,
    user_id: int,
    relation_id: int,
    original_text: str,
    context: Optional[str] = None,
) -> dict:
    """
    表达改写：将用户输入改写为5种不同风格版本
    - 温柔版：语气柔和，减少攻击性
    - 直接版：表达清晰但不伤人
    - 道歉版：真诚表达歉意
    - 解释版：理性解释而非辩解
    - 想和好版：表达修复关系的意愿
    """
    scene = ai_repo.get_scene_by_key(db, "expression_rewrite")
    if not scene:
        raise ValueError("50001")

    user_profile = profile_repo.get_latest_profile(db, user_id)
    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None

    partner_scores: Dict[str, float] = {}
    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}

    partner_profile_text = _format_profile(partner_profile, partner_scores)

    prompt = f"""你是一位专业的沟通顾问。请将以下原始表达改写为5种不同风格的版本。

## 原始表达
{original_text}

## 补充背景
{context or "无"}

## 对方画像
{partner_profile_text}

## 改写要求
请生成以下5个版本，每个版本都要保持核心意思不变，但调整语气和表达方式：

1. **温柔版**：语气柔和温暖，减少攻击性，让对方更容易接受
2. **直接版**：表达清晰直接，但不带伤害性，保持尊重
3. **道歉版**：真诚表达歉意和反思，承认自己的不足
4. **解释版**：理性解释自己的想法和感受，不带辩解语气
5. **想和好版**：表达想要修复关系的意愿，给出具体行动建议

## 输出格式
请以JSON格式输出，包含以下字段：
- summary: 改写总结（一句话说明主要调整）
- rewrites: 数组，包含5个对象，每个对象有 style（风格名称）和 content（改写内容）
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk
"""

    ai_response = _call_llm(prompt, "expression_rewrite")

    # 模型按 RewriteOutput schema 返回的字段是 rewrites（每项含 style / content），
    # 而不是 prompt 里口头描述的 versions。此前读 versions 永远命中不到，
    # 于是每次都白白返回 5 条硬编码占位文案（"[温柔版] 原文"），
    # 看起来"有结果"，实则与用户输入无关。
    versions = [
        {"style": r.get("style", ""), "content": r.get("content", "")}
        for r in (ai_response.get("rewrites") or [])
        if isinstance(r, dict) and r.get("content")
    ]
    if not versions:
        # 模型不可用（如未配置 AI_API_KEY）时如实告知，不再编造 5 条假版本
        reason = ai_response.get("raw_text") or "未能生成改写版本，请稍后重试。"
        versions = [{"style": "提示", "content": reason}]

    return {
        "original": original_text,
        "versions": versions,
        "summary": ai_response.get("summary") or "已为你生成改写版本",
    }


def get_sessions(db: Session, user_id: int) -> List[dict]:
    sessions = ai_repo.get_sessions_by_user(db, user_id)
    return [
        {
            "id": s.id,
            "scene_key": s.scene_key,
            "title": s.title,
            "privacy_level": s.privacy_level,
            "created_at": s.created_at,
            "updated_at": s.updated_at,
        }
        for s in sessions
    ]


def get_session_messages(db: Session, user_id: int, session_id: int) -> List[dict]:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")

    messages = ai_repo.get_messages_by_session(db, session_id)
    return [
        {
            "id": m.id,
            "role": m.role,
            "content": m.content,
            "structured_output": m.structured_output,
            "risk_level": m.risk_level,
            "created_at": m.created_at,
        }
        for m in messages
    ]


def submit_feedback(
    db: Session, user_id: int, session_id: int, message_id: int, feedback: dict
) -> None:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")

    ai_repo.create_feedback(
        db,
        message_id=message_id,
        user_id=user_id,
        rating=feedback["rating"],
        feedback_tag=feedback.get("feedback_tag"),
        feedback_text=feedback.get("feedback_text"),
    )
    db.commit()


def generate_profile_report(db: Session, user_id: int) -> str:
    """生成 AI 画像分析报告（Markdown 格式）"""
    from app.services.prompt_builder import build_profile_report_prompt
    from app.repositories import profile_repo

    profile = profile_repo.get_latest_profile(db, user_id)
    if not profile:
        raise ValueError("40001")

    dimensions = profile_repo.get_dimension_scores(db, profile.id)
    if not dimensions:
        raise ValueError("40001")

    dimension_names = {
        "attachment_anxiety": "依恋焦虑",
        "attachment_avoidance": "依恋回避",
        "conflict_pursue": "冲突追问倾向",
        "conflict_withdraw": "冲突退缩倾向",
        "defensive_response": "防御反驳倾向",
        "emotional_validation_need": "情绪确认需求",
        "factual_explanation_need": "事实解释需求",
        "personal_space_need": "独处冷静需求",
        "reassurance_need": "安全感确认需求",
        "directness_preference": "直接表达偏好",
        "softness_preference": "柔和表达偏好",
    }

    dim_lines = []
    for d in dimensions:
        name = dimension_names.get(d.dimension_key, d.dimension_key)
        explanation = d.explanation or ""
        dim_lines.append(f"- {name}：{d.score:.0f}分（{explanation}）")
    dimensions_text = "\n".join(dim_lines)

    prompt = build_profile_report_prompt(
        profile_type=profile.profile_type,
        confidence=profile.confidence,
        dimensions_data=dimensions_text,
    )

    # 画像报告是长文本 Markdown，不适用结构化输出，直接取原始文本
    try:
        return llm.invoke(
            [{"role": "system", "content": prompt}],
            temperature=0.6,
            max_tokens=2500,
            scene="profile_report",
        )
    except LlmError as exc:
        logger.error("[AI] 画像报告生成失败 user=%s: %s", user_id, exc)
        raise ValueError("50003")


def delete_session(db: Session, user_id: int, session_id: int) -> None:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")
    ai_repo.delete_session(db, session_id)


def _get_partner_id(db: Session, relation_id: int, user_id: int) -> Optional[int]:
    from app.models.couple_relation import CoupleRelation
    relation = db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()
    if not relation:
        return None
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def _format_profile(profile, scores: Dict[str, float]) -> str:
    if not profile:
        return "未完成问卷"
    type_names = {
        "secure": "安全型",
        "anxious": "焦虑依恋型",
        "dismissive": "疏离回避型",
        "fearful": "恐惧回避型",
    }
    ptype = type_names.get(profile.profile_type, profile.profile_type)
    dim_parts = []
    for k, v in scores.items():
        dim_parts.append(f"{k}={v}")
    dims_str = ", ".join(dim_parts) if dim_parts else "无"
    return f"依恋类型: {ptype}, 置信度: {profile.confidence}, 维度分数: [{dims_str}]"


def _call_llm(prompt: str, scene_key: str) -> dict:
    """调用真实大模型，返回结构化结果字典。

    函数签名与返回值结构保持与旧版占位实现完全一致，
    因此 `letter_ai_service` / `mediation_service` 无需改动即可受益。

    实现路径：
        prompt → llm.invoke_structured()（Function Calling 承载 Pydantic Schema）
              → 拿到强校验后的输出模型
              → model_dump() 展平成 dict，并补一个人类可读的 raw_text 供会话历史展示

    任何异常都不会向上抛，而是返回降级文案，保证对话不中断。
    """
    if not llm.api_key:
        logger.warning("[AI] 未配置 AI_API_KEY，返回降级回复 scene=%s", scene_key)
        return _degraded_response("AI 服务尚未配置，请联系管理员")

    messages = [{"role": "system", "content": prompt}]
    try:
        result = llm.invoke_structured(messages, scene=scene_key)
    except LlmError as exc:
        logger.error("[AI] 大模型调用失败 scene=%s: %s", scene_key, exc)
        return _degraded_response("AI 服务暂时不可用，请稍后重试")

    data = result.model_dump(mode="json")
    data["raw_text"] = _compose_raw_text(data, scene_key)
    data["scene_key"] = scene_key
    return data


def _compose_raw_text(data: dict, scene_key: str) -> str:
    """把结构化字段拼装成人类可读文本。

    用途：会话历史、消息列表折叠态、以及不支持结构化卡片的老版本客户端。
    """
    parts: List[str] = []

    if scene_key == "cold_war":
        if data.get("goal_analysis"):
            parts.append("【诉求分析】" + data["goal_analysis"])
        if data.get("face_vs_need"):
            parts.append("【面子 vs 需要】" + data["face_vs_need"])
        if data.get("approach_reason"):
            strategy = "主动破冰" if data.get("approach") == "approach" else "先给彼此空间"
            parts.append("【建议策略】%s——%s" % (strategy, data["approach_reason"]))
        openings = data.get("opening_lines") or []
        if openings:
            parts.append("【可以这样开口】\n" + "\n".join("- " + line for line in openings))
        avoid = data.get("avoid_reminders") or []
        if avoid:
            parts.append("【暂时别提】\n" + "\n".join("- " + item for item in avoid))

    elif scene_key == "expression_rewrite":
        if data.get("summary"):
            parts.append(data["summary"])
        for item in data.get("rewrites") or []:
            parts.append("【%s】%s" % (item.get("style", ""), item.get("content", "")))

    elif scene_key == "letter_understand":
        if data.get("surface_meaning"):
            parts.append("【字面意思】" + data["surface_meaning"])
        if data.get("underlying_need"):
            parts.append("【背后的需求】" + data["underlying_need"])
        if data.get("emotion_tone"):
            parts.append("【情绪基调】" + data["emotion_tone"])
        if data.get("suggested_reply"):
            parts.append("【建议回复】" + data["suggested_reply"])

    elif scene_key == "letter_analysis":
        if data.get("summary"):
            parts.append(data["summary"])
        if data.get("emotion"):
            parts.append("【对方的情绪】" + data["emotion"])
        for concern in data.get("key_concerns") or []:
            parts.append("【在意的点】" + concern)
        if data.get("expected_response"):
            parts.append("【期待的回应】" + data["expected_response"])
        for item in data.get("misunderstandable") or []:
            parts.append("【可能被误解】%s —— %s" % (item.get("sentence", ""), item.get("note", "")))
        for suggestion in data.get("reply_suggestions") or []:
            parts.append("【回信建议】" + suggestion)

    elif scene_key == "letter_rewrite":
        if data.get("summary"):
            parts.append(data["summary"])
        if data.get("rewritten_title"):
            parts.append("【改写后的标题】" + data["rewritten_title"])
        if data.get("rewritten_content"):
            parts.append("【改写后的正文】" + data["rewritten_content"])
        if data.get("changes"):
            parts.append("【改动说明】" + data["changes"])

    elif scene_key == "letter_reply":
        if data.get("summary"):
            parts.append(data["summary"])
        for item in data.get("replies") or []:
            parts.append("【%s】%s" % (item.get("style", ""), item.get("content", "")))
        if data.get("do_not_say"):
            parts.append("【避免说】" + data["do_not_say"])

    elif scene_key == "mediation_rewrite":
        if data.get("rewrite_a"):
            parts.append("【A 方的表达】" + data["rewrite_a"])
        if data.get("rewrite_b"):
            parts.append("【B 方的表达】" + data["rewrite_b"])

    elif scene_key == "mediation_summary":
        if data.get("common_points"):
            parts.append("【你们的共同点】\n" + "\n".join("- " + x for x in data["common_points"]))
        if data.get("differences"):
            parts.append("【仍有分歧的地方】\n" + "\n".join("- " + x for x in data["differences"]))
        if data.get("next_actions"):
            parts.append("【接下来可以做】\n" + "\n".join("- " + x for x in data["next_actions"]))

    else:
        label_map = [
            ("summary", ""),
            ("emotion_validation", "我理解你的感受："),
            ("partner_possible_meaning", "对方可能的真实意思："),
            ("suggested_reply", "建议这样回："),
            ("do_not_say", "不建议说："),
            ("next_step", "下一步："),
        ]
        for key, prefix in label_map:
            value = data.get(key)
            if value:
                parts.append(prefix + value if prefix else value)
        refs = data.get("theory_refs") or []
        if refs:
            parts.append("参考理论：" + "、".join(refs))

    return "\n\n".join(parts)


def _degraded_response(reason: str) -> dict:
    """降级兜底：保证结构化字段齐全，避免前端渲染异常。"""
    return {
        "raw_text": reason,
        "summary": reason,
        "emotion_validation": "我在这里，先陪你缓一缓。",
        "partner_possible_meaning": "",
        "suggested_reply": "",
        "do_not_say": "",
        "next_step": "稍后再试一次，或者先深呼吸，让自己平静下来。",
        "risk_level": "normal",
        "scene_key": "degraded",
    }
