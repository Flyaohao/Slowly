import logging
import os
import threading
from datetime import datetime, timedelta

from sqlalchemy.orm import Session
from typing import Any, Dict, Iterator, List, Optional, Tuple

from app.core.database import SessionLocal
from app.repositories import (
    ai_repo,
    avatar_repo,
    profile_repo,
    couple_repo,
    safety_repo,
)
from app.schemas.ai_output import RewriteOutput, ReviewOutput
from app.schemas.advisor_context import AdvisorContext
from app.services.prompt_builder import (
    CHAT_MODE_CONFIG,
    DEFAULT_AVATAR_NAME,
    build_prompt,
    build_structured_stream_prompt,
    build_persona_instruction,
    build_profile_report_prompt,
    build_dual_summary_prompt,
    build_practice_summary_prompt,
    build_memory_card_prompt,
    messages_to_text,
    resolve_chat_mode,
    resolve_system_prompt,
)
from app.services.lc_prompt_builder import build_chat_messages
from app.services.context_budget import fit_budget, layer_chars
from app.services.scene_router import get_scene_config
# 循环导入注意：ai_generation_service 也会 import 本模块的辅助函数，
# 不能放模块顶层，prepare_* 内部再导入。
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
from app.services.memory_retrieval import build_profile_keywords, retrieve_memory_items
from app.services.llm_client import llm, LlmError, get_client_for_mode
from app.services.sse import HEARTBEAT_INTERVAL, stream_with_heartbeat

logger = logging.getLogger("couple.ai")

#: 分段阈值（手册 D1）：距上一条消息超过 6 小时 / 会话累计 token 超 6000
SESSION_TIMEOUT = timedelta(hours=6)
SESSION_BUDGET_TOKENS = 6000


def should_start_new_session(session, scene_key: str, now: datetime) -> Optional[str]:
    """返回分段原因，None 表示可续接。按优先级自上而下判定（P0-10A 改动四）。

    1. session 为 None 或 status != 'active' → "archived"
    2. scene_key 不一致 → "scene_switch"
    3. now - last_message_at > 6h → "timeout"
    4. token_total > 6000 → "budget"
    5. 否则 None（可续接）
    """
    if session is None or getattr(session, "status", "active") != "active":
        return "archived"
    if session.scene_key != scene_key:
        return "scene_switch"
    last = getattr(session, "last_message_at", None) or getattr(session, "created_at", None)
    if last is not None and now - last > SESSION_TIMEOUT:
        return "timeout"
    if (getattr(session, "token_total", 0) or 0) > SESSION_BUDGET_TOKENS:
        return "budget"
    return None


def _archive_session_with_summary(
    db: Session, session, reason: str, user_id: int, relation_id: int
) -> None:
    """归档旧会话并异步沉淀 session_summary（改动七）。只归档不报错。

    收尾补丁 B：已 archived 直接返回——不改写 segment_reason（保住
    user_ended/timeout/scene_switch 的「这段为什么结束」），也不重复蒸馏。
    守卫放 service 而非 repo：archive_session 保持「归档即写原因」单一职责。
    """
    if getattr(session, "status", None) == "archived":
        return
    try:
        ai_repo.archive_session(db, session.id, reason)
    except Exception:
        logger.exception("[SESSION] 归档失败 id=%s", session.id)
        return
    # 后台蒸馏：不持请求级 db；开关与记忆抽取同一变量（测试隔离）
    from app.services.memory_service import distill_session_summary_in_background

    distill_session_summary_in_background(
        session_id=session.id,
        user_id=user_id,
        relation_id=relation_id,
        scene_key=session.scene_key,
    )


def _preprocess(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
    chat_mode: str = "deep",
) -> Dict[str, Any]:
    """聊天链路共享前处理（13 步里的 1~9 步）。

    `chat()`（阻塞）与 `prepare_chat()`（流式）共用这一份实现，避免两条路径各写一遍。
    返回值中 `blocked` 非空表示输入被安全护栏拦截，此时不创建会话、不调用模型。

    `chat_mode`（P-B §1）：quick/deep/expert，非法值回落 deep。**不是 `mode`**——
    后者是 lc_prompt_builder 的输出通道（structured|stream），撞名会错乱。
    本函数是历史/记忆/理论条数的实际决策点；档位尾部指令随 stream_messages
    组装传入，人格与 L0 基线的相对顺序由「build_chat_messages → _append_persona」
    固定为 场景(+L0) → 人格。

    注意：返回的 `session` 是 ORM 实例，只在请求级 `db` 存活期间可用，
    流式端点不要持有它（见 `prepare_chat`）。
    """
    mode = resolve_chat_mode(chat_mode)
    mode_cfg = CHAT_MODE_CONFIG[mode]

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

    # ---- P0-10A 改动四：分段 / 续接（服务端权威）----
    now = datetime.now()
    session = None
    segment_reason: Optional[str] = None

    if session_id:
        session = ai_repo.get_session_by_id(db, session_id)
        if not session or session.user_id != user_id:
            raise ValueError("50002")
        # 显式传入：归属校验后照旧使用；scene 不一致 → 按分段处理（修切场景串味）
        if session.scene_key != scene_key or session.status != "active":
            reason = (
                "scene_switch" if session.scene_key != scene_key else "archived"
            )
            _archive_session_with_summary(db, session, reason, user_id, relation_id)
            segment_reason = reason
            session = None
    else:
        active = ai_repo.get_active_session(db, user_id, relation_id, scene_key)
        if active is not None:
            reason = should_start_new_session(active, scene_key, now)
            if reason is None:
                session = active  # 续接
            else:
                _archive_session_with_summary(db, active, reason, user_id, relation_id)
                segment_reason = reason
                session = None

    if session is None:
        scene_config = get_scene_config(scene_key)
        privacy = scene_config.get("privacy_level", "private")
        session = ai_repo.create_session(
            db,
            user_id,
            relation_id,
            scene_key,
            title=scene.name,
            privacy_level=privacy,
            segment_reason=segment_reason,
        )
    session_id = session.id

    history = ai_repo.get_messages_by_session(db, session_id)
    # P-C2 §2：历史**不再在此内联截断**——条数（budget_h）与字符总额
    # 统一交给 context_budget.fit_budget（全链路唯一裁剪层）。

    user_profile_text = _format_profile(user_profile, user_scores, db, user_id)
    # 伴侣段：有画像用「TA 的画像」卡，双方都有时再拼「你们的关系」；
    # 对方未完成问卷 → 整段留空（不输出占位，避免模型把占位当事实）。
    # 拆成两块是为了 evidence 展示（P0-5）能分开渲染；prompt 仍拼成一段。
    partner_card, rel_block = _build_partner_parts(
        user_profile, user_scores, partner_profile, partner_scores, db, partner_id
    )
    partner_profile_text = (
        f"{partner_card}\n\n{rel_block}" if rel_block else partner_card
    )

    # prompt 版本化：优先取 DB 中该场景 active 的模板（多版本时按 user_id 稳定分流），
    # 取不到才回退代码内置文案。结构化与流式两条出口共用同一份模板——
    # 若各解析一次，两个 active 版本并存时可能出现「结构化用 v2、流式用 v1」的分叉。
    # P-C2 §2：上移到预算之前——S 层（场景 system + 人格）要在裁剪前量出来。
    system_template = resolve_system_prompt(scene_key, db=db, user_id=user_id)

    # P0-7 军师人格：读 ai_avatar（名字 + 语气），指令追加到 system 最后一段。
    # 结构化与流式两条出口都加，保证「换语气后回答风格可观察地不同」两条链路一致。
    # 取不到 avatar（未捏过脸）用默认人格，不报错。
    # P-C2 §2：同样上移——人格计入 S 层长度。
    avatar = avatar_repo.get_avatar_by_relation_id(db, relation_id)
    persona = build_persona_instruction(
        avatar.name if avatar else None,
        avatar.voice_style if avatar else None,
    )

    # ---- P-C2 §2：S 层长度 = 空上下文渲染的场景 system（两出口取较长）
    # + 人格。system 只含 S/P/H 变量，与本轮 U/E/M 无关，空渲染即固定成本。
    _empty_ctx = dict(
        scene_key=scene_key,
        user_profile="",
        partner_profile="",
        conflict_pattern="",
        user_input="",
        history="",
        rag_context="",
        memory_context="",
    )
    _s_struct = build_chat_messages(
        mode="structured", system_template=system_template, **_empty_ctx
    )[0]["content"]
    _s_stream = build_chat_messages(
        mode="stream",
        system_template=system_template,
        stream_instruction=mode_cfg["instruction"],
        **_empty_ctx,
    )[0]["content"]
    s_text = _s_struct if len(_s_struct) >= len(_s_stream) else _s_stream
    s_text = _append_persona(
        [{"role": "system", "content": s_text}], persona
    )[0]["content"]

    # P-B §1.2：理论 RAG 条数按档位取（quick 0=关闭 / deep 3 / expert 5）
    _rk = mode_cfg["rag_top_k"]
    rag_chunks = retrieve_chunks(db, user_input, top_k=_rk) if _rk else []

    # P0-4：召回一次、两处使用（约束③）——prompt 注入与 evidence 展示
    # 必须是同一份结果，否则面板显示近因 10 条、prompt 用相关性 5 条
    # 就造出 P0-5 要消灭的那种分叉。
    # P-B §1.2：记忆召回条数按档位取（quick 0=不查 / deep 5 / expert 10+事件时间线）。
    # 召回各路径（含降级）已按 limit 截断；这里再在调用侧截一道，把档位矩阵
    # 钉死在消费侧——即使日后召回实现改动，三档条数也不会越过矩阵。
    #
    # Stage 2（P-C2 §3）：画像驱动 query——冲突模式 + 依恋类型名 +
    # 近期 importance>=1 事件标题词；取不到就跳过，不塞占位。
    # 关键词在召回入口（retrieve_memory_items）拼进 query（build_profile_query），
    # 本轮原话单独走 echo 惩罚——两者分离，画像词不算「用户说过的话」。
    _ml = mode_cfg["memory_limit"]
    profile_keywords: List[str] = []
    if _ml:
        profile_keywords = build_profile_keywords(
            db,
            user_id,
            relation_id,
            conflict_pattern=conflict_pattern,
            profile_type=user_profile.profile_type if user_profile else None,
            partner_profile_type=partner_profile.profile_type if partner_profile else None,
        )
        memory_items = retrieve_memory_items(
            db,
            user_id,
            relation_id,
            query=user_input,
            limit=_ml,
            profile_keywords=profile_keywords,
            scene_key=scene_key,
        )[:_ml]
    else:
        memory_items = []
    from app.services.memory_retrieval import format_memory_context

    # ---- P-C2 §2：分层预算——全链路唯一裁剪层（唯一调用点）----
    sections = dict(
        s=s_text,
        p="\n".join(
            x for x in (
                user_profile_text,
                partner_profile_text,
                conflict_pattern or "未确定",
            ) if x
        ),
        u=user_input,
        h=[f"{m.role}: {m.content}" for m in history],
        e=memory_items,
        m=rag_chunks,
    )
    fitted, budget_omitted, needs_archive = fit_budget(sections, mode_cfg)

    if needs_archive and history:
        # 规则 2：超上限不硬塞 → 归档（复用 should_start_new_session 的
        # "budget" 分支语义），历史清零后重算一次。
        _archive_session_with_summary(db, session, "budget", user_id, relation_id)
        segment_reason = "budget"
        privacy = get_scene_config(scene_key).get("privacy_level", "private")
        session = ai_repo.create_session(
            db,
            user_id,
            relation_id,
            scene_key,
            title=scene.name,
            privacy_level=privacy,
            segment_reason="budget",
        )
        session_id = session.id
        sections["h"] = []
        fitted, budget_omitted, needs_archive = fit_budget(sections, mode_cfg)
        if needs_archive:
            logger.error(
                "[BUDGET] 归档后仍超上限（S/P/U 固定成本降不下来） mode=%s "
                "total=%d budget_total=%d",
                mode,
                sum(layer_chars(fitted).values()),
                mode_cfg.get("budget_total"),
            )
    elif needs_archive:
        logger.error(
            "[BUDGET] 超上限但无可归档历史（新会话即超） mode=%s", mode
        )
    if budget_omitted:
        logger.info("[BUDGET] 本轮省略: %s", "、".join(budget_omitted))

    # ---- 消费 fitted：h/e/m 可能被裁，P/U/S 原样（永不裁）----
    history_text = "\n".join(fitted["h"])
    rag_chunks = fitted["m"]
    rag_context = build_rag_context(rag_chunks)
    memory_items = fitted["e"]
    memory_context = format_memory_context(memory_items)

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
    # 同一份上下文，两种出口：结构化 JSON 版 / 自然语言流式版。
    # 二者都由 LangChain 组装成 system + human 分层消息（见 lc_prompt_builder）：
    # 人设与画像进 system，检索上下文、长期记忆与本次输入进 human。
    # P-C2 §2 坑②：两出口共用同一份 prompt_args（fitted 只裁一次），
    # 结构化/流式注入内容不会分叉。
    messages = build_chat_messages(
        **prompt_args, mode="structured", system_template=system_template
    )
    stream_messages = build_chat_messages(
        **prompt_args,
        mode="stream",
        system_template=system_template,
        stream_instruction=mode_cfg["instruction"],
    )
    messages = _append_persona(messages, persona)
    stream_messages = _append_persona(stream_messages, persona)

    # P0-5 判断依据：与 prompt 同一份 memory_items（约束③——同请求不再
    # 跑第二次同义查询；面板看到什么，模型就看到什么）。
    evidence = AdvisorContext(
        scene_key=scene_key,
        # 无问卷但有星座/MBTI 时也展示（那是真实资料）；纯占位「未完成问卷」不进面板
        self_profile_card=(
            "" if user_profile_text == "未完成问卷" else user_profile_text
        ),
        partner_profile_card=partner_card,
        relationship_pattern=rel_block,
        recalled_memories=[
            {
                "content": m.get("content", ""),
                "source": m.get("source", ""),
                "created_at": m.get("created_at"),
            }
            for m in memory_items
        ],
        theory_chunks=[
            {
                "title": c.get("doc_title", ""),
                "snippet": (c.get("chunk_text") or "")[:80],
                "score": c.get("score", 0),
            }
            for c in rag_chunks
        ],
        avatar_name=(avatar.name if avatar else "") or DEFAULT_AVATAR_NAME,
        voice_style=(avatar.voice_style if avatar else "") or "gentle",
        # P-C2 §5：本轮省略了什么（分层预算裁剪说明，空则前端整栏不显示）
        omitted=budget_omitted,
    ).to_display()

    ai_repo.create_message(db, session_id, "user", user_input, chat_mode=mode)
    # 留档用压平后的文本，便于直接读「这一轮到底给模型看了什么」
    ai_repo.save_prompt_version(
        db, user_id, relation_id, scene_key, messages_to_text(messages)
    )

    # 必须在此提交，不能留到调用方：
    # 流式端点会先带着这份数据返回 StreamingResponse，请求级 db 随即被销毁，
    # 未提交的会话行与用户消息会被回滚，导致随后独立会话落库助手消息时外键失败。
    db.commit()

    return {
        "blocked": None,
        "session": session,
        "session_id": session_id,
        "scene": scene,
        "messages": messages,
        "stream_messages": stream_messages,
        "user_input": user_input,
        "rag_chunks": rag_chunks,
        "evidence": evidence,
        "chat_mode": mode,
    }


def chat(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
    chat_mode: str = "deep",
) -> dict:
    ctx = _preprocess(
        db, user_id, relation_id, session_id, scene_key, user_input,
        chat_mode=chat_mode,
    )

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
            "evidence": None,
        }

    scene = ctx["scene"]
    session = ctx["session"]
    session_id = ctx["session_id"]

    ai_response = _call_llm(ctx["messages"], scene_key)

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
        chat_mode=ctx.get("chat_mode"),
    )

    session.title = session.title or scene.name
    db.commit()

    # P0-10A 改动六：第 1 轮回答落库后异步生成 ≤12 字标题
    if (session.message_count or 0) <= 2:
        _schedule_session_title(
            session_id=session_id,
            user_text=ctx["user_input"],
            assistant_text=assistant_msg.content,
            scene_name=scene.name,
            scene_key=scene_key,
        )

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
        # P0-5：判断依据随非流式响应一出（已在 _preprocess 拍平，无 ORM）
        "evidence": ctx.get("evidence"),
    }


def prepare_chat(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
    chat_mode: str = "deep",
) -> dict:
    """SSE 端点专用：只做前处理，返回**纯数据**（不含 ORM 对象）。

    为什么不能直接把 `_preprocess` 的结果交给生成器：
    `StreamingResponse` 的响应体是在请求级 `db` 会话之后才被消费的，
    此时 `ctx["session"]` 已经 Detached，再访问属性会抛异常。
    所以这里立刻把需要的信息拍平成基本类型，生成器只认这些数据。
    """
    ctx = _preprocess(
        db, user_id, relation_id, session_id, scene_key, user_input,
        chat_mode=chat_mode,
    )

    if ctx["blocked"]:
        return {
            "blocked": True,
            "content": ctx["blocked"],
            "risk_level": ctx["risk_level"],
            "session_id": ctx["session_id"],
            "scene_key": scene_key,
            "chat_mode": ctx.get("chat_mode", "deep"),
        }

    return {
        "blocked": False,
        "session_id": ctx["session_id"],
        "scene_key": scene_key,
        "scene_name": ctx["scene"].name,
        "stream_messages": ctx["stream_messages"],
        "chat_mode": ctx.get("chat_mode", "deep"),
        "rag_hit": len(ctx["rag_chunks"]),
        # P0-5：evidence 已是纯 dict，可在响应体阶段随生成器携带
        "evidence": ctx.get("evidence"),
        # 记忆沉淀需要在响应体阶段（请求级 db 已销毁）用独立会话写库，
        # 故这里把落库所需的三个基本类型一并拍平带过去。
        "user_id": user_id,
        "relation_id": relation_id,
        "user_input": user_input,
    }


def _stream_with_heartbeat(
    messages: List[Dict[str, Any]],
    scene_key: str,
    interval: float = HEARTBEAT_INTERVAL,
    max_tokens: int = 1200,
    client=None,
) -> Iterator[Optional[Tuple[str, str]]]:
    """产出 `(kind, text)` 增量，静默期产出 `None` 作为心跳信号。

    真正的实现在 `app.services.sse.stream_with_heartbeat`（心跳机制、取消信号、
    后台线程的说明都在那边），这里只做一层「消息列表 + 采样参数」的适配，
    免得每个调用方都手写一遍。

    `messages` 由 `lc_prompt_builder.build_chat_messages` 组装（system 承载人设
    与画像，human 承载检索上下文、长期记忆与本次输入）。

    `max_tokens` / `client`：P-B §1.3——正文长度上限曾在这里硬编码 1200，
    三档必须透传各自的值（quick 600 / deep 1200 / expert 2000），否则 quick
    永远突破不了上限；思考开关在客户端实例上，随 client 一并传入。
    """
    return stream_with_heartbeat(
        messages,
        scene_key,
        temperature=0.7,
        max_tokens=max_tokens,
        interval=interval,
        client=client,
    )


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
            # P-C3 §3.2：用量进度的刷新点之一（进页面/ meta / done，不逐帧）
            "token_total": _read_session_token(prepared["session_id"]),
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
    # P-B §1.3：按档位取正文上限与客户端实例（deep 即既有行为）
    _mode = resolve_chat_mode(prepared.get("chat_mode"))
    _mode_cfg = CHAT_MODE_CONFIG[_mode]
    try:
        for item in _stream_with_heartbeat(
            prepared["stream_messages"],
            prepared["scene_key"],
            max_tokens=_mode_cfg["max_tokens"],
            client=get_client_for_mode(_mode),
        ):
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

    message_id, token_after = _persist_streamed_message(
        session_id=prepared["session_id"],
        content=full_text,
        structured=structured,
        risk_level=output_risk,
        user_id=prepared.get("user_id"),
        relation_id=prepared.get("relation_id"),
        scene_key=prepared["scene_key"],
        user_input=prepared.get("user_input", ""),
        chat_mode=prepared.get("chat_mode"),
    )

    # P0-5：正文完整后的末帧之一——判断依据。独立事件名，不与 thinking/delta 混用；
    # 数据在 prepare_chat 已拍平，此处不碰 db。
    evidence = prepared.get("evidence")
    if evidence:
        yield {"event": "evidence", "data": evidence}

    yield {
        "event": "done",
        "data": {
            "session_id": prepared["session_id"],
            "message_id": message_id,
            "risk_level": output_risk,
            "blocked": False,
            "content": full_text,
            "finish_reason": "stop",
            # P-C3 §3.2：落库后的最新用量（token_total 只有服务端落库后才有意义）
            "token_total": token_after,
        },
    }


def _read_session_token(session_id: Optional[int]) -> int:
    """读当前会话 token_total（P-C3 §3.2：meta 帧用量）。

    自开独立会话：流式生成器跑在响应体阶段，请求级 db 已销毁，
    prepared["session"] 是 Detached 且 commit 后属性过期，不能直接读。
    任何异常回 0——用量展示不值得让流失败。
    """
    if session_id is None:
        return 0
    try:
        db = SessionLocal()
        try:
            from app.models.ai import AiChatSession as _Sess

            sess = db.query(_Sess).filter(_Sess.id == session_id).first()
            return int(getattr(sess, "token_total", 0) or 0) if sess else 0
        finally:
            db.close()
    except Exception:
        logger.warning("[AI] 读取 session token_total 失败 session=%s", session_id, exc_info=True)
        return 0


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
    chat_mode: Optional[str] = None,
) -> Tuple[int, int]:
    """流式结束后落库。

    用独立会话，且把异常吞掉只记日志：内容此刻已经推给用户了，
    落库失败不应该反过来影响这次回答的观感。返回 (0, 0) 表示落库失败。
    P-C3 §3.2：同时回传落库后的 session.token_total（done 帧用量刷新点）。
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
            chat_mode=chat_mode,
        )
        db.commit()
        # P0-10A 改动六：流式链路同样在第 1 轮回答后生成标题
        from app.models.ai import AiChatSession as _Sess

        sess = db.query(_Sess).filter(_Sess.id == session_id).first()
        token_after = int(getattr(sess, "token_total", 0) or 0) if sess else 0
        if sess is not None and (sess.message_count or 0) <= 2:
            scene = ai_repo.get_scene_by_key(db, scene_key)
            _schedule_session_title(
                session_id=session_id,
                user_text=user_input,
                assistant_text=content,
                scene_name=scene.name if scene else scene_key,
                scene_key=scene_key,
            )
        # 记忆沉淀同样走后台线程 + 独立会话：本函数所在阶段请求级 db 已经销毁，
        # 不能复用这里的 db 之外的任何会话。
        if user_id and relation_id:
            distill_in_background(
                user_id, relation_id, scene_key, user_input, content
            )
        return msg.id, token_after
    except Exception:
        db.rollback()
        logger.exception("[AI] 流式消息落库失败 session=%s", session_id)
        return 0, 0
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

    partner_profile_text = _format_profile(partner_profile, partner_scores, db, partner_id)

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
            # P0-10A：列表可展示会话边界信息（向后兼容，老客户端忽略新字段）
            "status": s.status,
            "message_count": s.message_count or 0,
            "last_message_at": s.last_message_at,
            "segment_reason": s.segment_reason,
        }
        for s in sessions
    ]


def get_active_session_info(
    db: Session, user_id: int, relation_id: int, scene_key: str
) -> Dict[str, Any]:
    """GET /ai/sessions/active 的服务层（P0-10A 改动五）。

    resumable=true  → 可直接续接
    resumable=false 且 session_id 非空 → 返回「最近一段」供展示
                     （超时/超预算，客户端提示上次聊到…继续还是新开）
    session_id=null → 该 scope 无 active 会话
    """
    session = ai_repo.get_active_session(db, user_id, relation_id, scene_key)
    if session is None:
        # P-C3 §3.1：无 active 会话时新键也必须在（客户端 Moshi 默认值兜底，
        # 但进度条/分段原因逻辑要拿到 budget 才能算比例）。
        return {
            "session_id": None,
            "title": None,
            "message_count": 0,
            "last_message_at": None,
            "resumable": False,
            "token_total": 0,
            "budget": SESSION_BUDGET_TOKENS,
            "archive_reason": None,
        }
    reason = should_start_new_session(session, scene_key, datetime.now())
    # get_active_session 已按 scene_key + status=active 过滤，
    # 此处 reason 只可能是 None / timeout / budget
    return {
        "session_id": session.id,
        "title": session.title,
        "message_count": session.message_count or 0,
        "last_message_at": (
            session.last_message_at.isoformat() if session.last_message_at else None
        ),
        "resumable": reason is None,
        # P-C3 §3.1：上下文用量可见化三键（只加不删，向后兼容）
        "token_total": session.token_total or 0,
        "budget": SESSION_BUDGET_TOKENS,
        "archive_reason": reason,
    }


def _schedule_session_title(
    session_id: int,
    user_text: str,
    assistant_text: str,
    scene_name: str,
    scene_key: str,
) -> None:
    """P0-10A 改动六：第 1 轮回答后异步生成 ≤12 字标题。

    只在 title 仍为场景名/空时覆盖；LLM 失败回退用户输入前 12 字；
    异常只记日志。测试隔离开关与记忆抽取共用（避免测试真调模型）。
    """
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return None

    def _worker():
        from app.core.database import SessionLocal
        from app.services.memory_service import distill_llm

        db = SessionLocal()
        try:
            session = ai_repo.get_session_by_id(db, session_id)
            if session is None:
                return
            # 已是语义标题（非场景名）→ 不覆盖，避免冲掉用户/历史改名
            if session.title and session.title != scene_name:
                return

            fallback = (user_text or "").strip()[:12]
            title = fallback
            try:
                if distill_llm.api_key:
                    raw = distill_llm.invoke(
                        [
                            {
                                "role": "system",
                                "content": (
                                    "根据对话生成一个不超过12个字的中文标题，"
                                    "只输出标题本身，不要引号、标点或解释。"
                                ),
                            },
                            {
                                "role": "user",
                                "content": "用户：%s\n助手：%s"
                                % ((user_text or "")[:200], (assistant_text or "")[:200]),
                            },
                        ],
                        scene="session_title",
                        temperature=0.3,
                        max_tokens=30,
                    )
                    raw = (raw or "").strip().strip("「」\"'")[:12]
                    if raw:
                        title = raw
            except Exception:
                logger.warning("[SESSION] 标题 LLM 失败，回退前12字", exc_info=True)

            if title:
                session.title = title
                db.commit()
                logger.info("[SESSION] 标题已更新 session=%s → %s", session_id, title)
        except Exception:
            logger.exception("[SESSION] 标题生成异常 session=%s", session_id)
            db.rollback()
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="session-title", daemon=True
    ).start()
    return None


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


def close_session(db: Session, user_id: int, session_id: int) -> Dict[str, Any]:
    """P0-10B 前置：用户显式「新对话 / 结束这段对话」。

    - 归档 + 触发 session_summary 蒸馏（复用 _archive_session_with_summary，
      reason="user_ended"——同时补上 P0-10A 遗留 e.7）
    - 幂等：已 archived 再调仍返回 200，不重复蒸馏
    - 非本人 / 不存在 → ValueError("50002")，由端点转 403
    """
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")
    if session.status != "archived":
        _archive_session_with_summary(
            db, session, "user_ended", user_id, session.relation_id
        )
        db.commit()
    return {"closed": True, "session_id": session_id}


def _get_partner_id(db: Session, relation_id: int, user_id: int) -> Optional[int]:
    from app.models.couple_relation import CoupleRelation
    relation = db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()
    if not relation:
        return None
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def _format_profile(
    profile,
    scores: Dict[str, float],
    db: Optional[Session] = None,
    person_id: Optional[int] = None,
) -> str:
    """画像注入的唯一出口（用户侧）。

    由 profile_service.build_profile_card 产出中文语义卡（维度名 +
    行为化解读 + 沟通宜忌），替换原先「英文 key=分数」的参数表——
    模型不再需要自己翻译 attachment_anxiety=72 是什么意思。

    `db` / `person_id` 用于顺带注入星座·星盘 + MBTI 性格辅助块
    （astrology_service）：画像卡是问卷结论，辅助块是补充了解渠道，
    两者并列给模型，块尾自带参考权重口径。不传也能用（老调用方兼容）。
    """
    block = _personality_block(db, person_id)
    if not profile:
        base = "未完成问卷"
    else:
        from app.services.profile_service import build_profile_card

        base = build_profile_card(profile.profile_type, scores, profile.confidence)
    if not block:
        return base
    return f"{base}\n{block}" if base == "未完成问卷" else f"{base}\n\n{block}"


def _personality_block(db: Optional[Session], person_id: Optional[int]) -> str:
    """星座/星盘/MBTI 辅助块（取不到资料时返回空串，不抛错、不塞占位）。"""
    if db is None or person_id is None:
        return ""
    try:
        from app.repositories import user_repo
        from app.services.astrology_service import build_personality_block

        basic = user_repo.get_profile_by_user_id(db, person_id)
        if basic is None:
            return ""
        return build_personality_block(
            basic.birthday, basic.birth_hour, basic.mbti, basic.birth_place
        )
    except Exception:
        logger.warning("[PROFILE] 性格辅助块拼装失败（不影响画像注入）", exc_info=True)
        return ""


def _append_persona(messages: List[Dict[str, Any]], persona: str) -> List[Dict[str, Any]]:
    """把人格指令追加到 system 消息末尾，返回新列表（不改原消息）。

    system 是 messages[0]（lc_prompt_builder 的约定）；非 system 消息原样透传。
    """
    if not persona:
        return messages
    out: List[Dict[str, Any]] = []
    for m in messages:
        if m.get("role") == "system":
            out.append({**m, "content": m["content"] + "\n\n" + persona})
        else:
            out.append(m)
    return out


def _build_partner_parts(
    user_profile,
    user_scores: Dict[str, float],
    partner_profile,
    partner_scores: Dict[str, float],
    db: Optional[Session] = None,
    partner_id: Optional[int] = None,
) -> Tuple[str, str]:
    """拆出伴侣段的两块：(TA 画像卡, 关系模式块)。

    对方无问卷画像 → ("", "")：不输出占位（P0-2）；但对方有星座/MBTI 时仍出
    性格辅助块——那是真实资料不是占位，军师判断 TA 时同样用得上。
    己方无画像 → 只出 TA 卡、关系块为空（关系需要双方分数）。
    供 prompt 拼装（`_build_partner_section`）与 evidence 展示共用同一判定。
    """
    personality = _personality_block(db, partner_id)

    if not partner_profile:
        return (personality, "") if personality else ("", "")

    from app.services.profile_service import (
        build_profile_card,
        derive_relationship_pattern,
    )

    partner_card = build_profile_card(
        partner_profile.profile_type,
        partner_scores,
        partner_profile.confidence,
        title="TA 的画像",
    )
    if personality:
        partner_card = f"{partner_card}\n\n{personality}"
    if not user_profile:
        return partner_card, ""
    pattern_name, pattern_desc = derive_relationship_pattern(
        user_scores, partner_scores
    )
    rel_block = f"【你们的关系】「{pattern_name}」型\n{pattern_desc}"
    return partner_card, rel_block


def _build_partner_section(
    user_profile,
    user_scores: Dict[str, float],
    partner_profile,
    partner_scores: Dict[str, float],
    db: Optional[Session] = None,
    partner_id: Optional[int] = None,
) -> str:
    """伴侣画像段 + 关系模式段（P0-2），prompt 拼装入口。

    - 对方无画像 → 返回空串：不输出「TA 的画像」也不输出「你们的关系」，
      更不输出「未完成问卷」占位（占位会被模型当成事实）
    - 对方有画像、己方也有 → TA 卡之后追加「你们的关系」
    - 对方有画像、己方没有 → 只出 TA 卡，不判关系

    `_preprocess` 与测试共用本函数，保证验收打的是真实拼装路径。
    """
    partner_card, rel_block = _build_partner_parts(
        user_profile, user_scores, partner_profile, partner_scores, db, partner_id
    )
    if not partner_card:
        return ""
    if rel_block:
        return f"{partner_card}\n\n{rel_block}"
    return partner_card


def _call_llm(prompt: Any, scene_key: str) -> dict:
    """调用真实大模型，返回结构化结果字典。

    函数签名与返回值结构保持与旧版占位实现完全一致，
    因此 `letter_ai_service` / `mediation_service` 无需改动即可受益。

    `prompt` 兼容两种入参：

    - **消息列表**（`[{role, content}, …]`）：聊天主链路走这条，
      由 `lc_prompt_builder.build_chat_messages` 组装成 system + human 分层。
    - **单条字符串**：信件解读/改写、调解等自行拼装 prompt 的场景沿用，
      内部包成一条 system 消息——与该函数此前的行为逐字节一致。

    实现路径：
        messages → llm.invoke_structured()（Function Calling 承载 Pydantic Schema）
                 → 拿到强校验后的输出模型
                 → model_dump() 展平成 dict，并补一个人类可读的 raw_text 供会话历史展示

    任何异常都不会向上抛，而是返回降级文案，保证对话不中断。
    """
    if not llm.api_key:
        logger.warning("[AI] 未配置 AI_API_KEY，返回降级回复 scene=%s", scene_key)
        return _degraded_response("AI 服务尚未配置，请联系管理员")

    messages = (
        [{"role": "system", "content": prompt}]
        if isinstance(prompt, str)
        else list(prompt)
    )
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


# --------------------------------------------------------------------------- #
# 单次触发型生成的流式前处理（prepare_*）
#
# 与 letter_ai_service.prepare_understand_letter 同一模式：请求级 db 存活期间
# 完成校验 + 拼 Prompt + 占位 ai_generation 记录，返回 dict 交给
# ai_generation_service.stream_generation_events 推 SSE。
# --------------------------------------------------------------------------- #

def prepare_rewrite_expression(
    db: Session,
    user_id: int,
    relation_id: int,
    original_text: str,
    context: Optional[str] = None,
) -> dict:
    """流式版「帮我表达」（表达改写）的前处理。

    共用同步版 `rewrite_expression` 的 Prompt 与输出模型（RewriteOutput）。
    正文流按 5 个风格逐段输出改写全文，分隔符后 JSON 承载结构化版本数组。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环

    scene = ai_repo.get_scene_by_key(db, "expression_rewrite")
    if not scene:
        raise ValueError("50001")

    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None
    partner_scores: Dict[str, float] = {}
    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}
    partner_profile_text = _format_profile(partner_profile, partner_scores, db, partner_id)

    base_prompt = f"""你是一位专业的沟通顾问。请将以下原始表达改写为5种不同风格的版本。

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
    prompt = build_structured_stream_prompt(
        base_prompt,
        RewriteOutput,
        content_instruction=(
            "把 5 个版本的改写结果作为正文完整输出，每个版本以【风格名】开头，"
            "风格顺序与要求一致"
        ),
        max_content_chars=1500,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind="expression_rewrite",
        scene_key="expression_rewrite",
        target_type="none",
        target_id=None,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "expression_rewrite",
        "scene_key": "expression_rewrite",
        "target_type": "none",
        "target_id": None,
        "user_id": user_id,
        "relation_id": relation_id,
        "prompt": prompt,
        "output_model": RewriteOutput,
        "temperature": 0.7,
        "max_tokens": 4000,
    }


def prepare_profile_report(db: Session, user_id: int) -> dict:
    """流式版「AI 画像报告」的前处理。

    画像报告是长 Markdown，没有结构化字段，`output_model` 置 None——
    `stream_generation_events` 会把整段输出当正文流式下发。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环

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

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=None,  # 画像是个人维度，不挂关系（v2.2 起可空）
        generation_kind="profile_report",
        scene_key="profile_report",
        target_type="none",
        target_id=None,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "profile_report",
        "scene_key": "profile_report",
        "target_type": "none",
        "target_id": None,
        "user_id": user_id,
        "relation_id": None,
        "prompt": prompt,
        "output_model": None,
        "temperature": 0.6,
        "max_tokens": 2500,
    }


def prepare_dual_summary(db: Session, user_id: int, event_id: int) -> dict:
    """流式版「双视角 AI 总结」的前处理。

    纯 Markdown 长文，无结构化字段（output_model=None）。
    只有双方都已提交记录后才允许总结——单方视角做不出「对照」。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环
    from app.services import dual_perspective_service

    # get_event_detail 内部已校验关系归属，错误码（70001/70002）原样透传
    event = dual_perspective_service.get_event_detail(db, user_id, event_id)

    records = list(getattr(event, "records", None) or [])
    if len(records) < 2:
        raise ValueError("70003")

    self_text = ""
    partner_text = ""
    for r in records:
        text = (r.content or "").strip()
        if r.user_id == user_id:
            self_text = text
        else:
            partner_text = text

    prompt = build_dual_summary_prompt(
        event_title=event.title,
        side_self=self_text,
        side_partner=partner_text,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=event.relation_id,
        generation_kind="dual_summary",
        scene_key="dual_summary",
        target_type="dual_event",
        target_id=event_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "dual_summary",
        "scene_key": "dual_summary",
        "target_type": "dual_event",
        "target_id": event_id,
        "user_id": user_id,
        "relation_id": event.relation_id,
        "prompt": prompt,
        "output_model": None,
        "temperature": 0.6,
        "max_tokens": 2000,
    }


def prepare_practice_summary(db: Session, user_id: int, record_id: int) -> dict:
    """流式版「关系练习 AI 整理」的前处理（纯 Markdown 长文）。

    至少有一方作答才整理；双方都作答时做对照，只差一方时只整理已有内容。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环
    from app.services import practice_service

    # get_record 内部已校验关系归属，错误码（90002/90003）原样透传
    record = practice_service.get_record(db, user_id, record_id)

    self_text = (record.get("my_submission") or "").strip()
    partner_text = (record.get("partner_submission") or "").strip()
    if not self_text and not partner_text:
        raise ValueError("90004")

    prompt = build_practice_summary_prompt(
        practice_title=record.get("practice_title") or "关系练习",
        side_self=self_text,
        side_partner=partner_text,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=record["relation_id"],
        generation_kind="practice_summary",
        scene_key="practice_summary",
        target_type="practice_record",
        target_id=record_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "practice_summary",
        "scene_key": "practice_summary",
        "target_type": "practice_record",
        "target_id": record_id,
        "user_id": user_id,
        "relation_id": record["relation_id"],
        "prompt": prompt,
        "output_model": None,
        "temperature": 0.6,
        "max_tokens": 2000,
    }


def prepare_memory_card(db: Session, user_id: int, target_type: str, target_id: int) -> dict:
    """流式版「回忆卡片」的前处理（纯 Markdown 长文）。

    target_type 仅支持 `anniversary` / `wishlist`——两条材料都来自
    `anniversary_repo`，权限校验沿用 100001（不存在）/ 100002（不属于当前关系）。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环
    from app.repositories import anniversary_repo

    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")

    if target_type == "anniversary":
        item = anniversary_repo.get_anniversary_by_id(db, target_id)
        if not item:
            raise ValueError("100001")
        if item.relation_id != relation.id:
            raise ValueError("100002")
        item_kind = "纪念日"
        item_detail = "\n".join(
            part
            for part in [
                f"标题：{item.title}",
                f"日期：{item.anniversary_date}",
                f"描述：{(item.description or '').strip() or '（无）'}",
            ]
            if part
        )
    elif target_type == "wishlist":
        item = anniversary_repo.get_wishlist_by_id(db, target_id)
        if not item:
            raise ValueError("100001")
        if item.relation_id != relation.id:
            raise ValueError("100002")
        item_kind = "愿望"
        status_text = "已完成" if item.status == "completed" else "期待中"
        completed = (
            f"，完成于 {item.completed_at:%Y-%m-%d}" if item.completed_at else ""
        )
        item_detail = "\n".join(
            [
                f"标题：{item.title}",
                f"状态：{status_text}{completed}",
                f"描述：{(item.description or '').strip() or '（无）'}",
            ]
        )
    else:
        raise ValueError("100003")

    prompt = build_memory_card_prompt(item_kind=item_kind, item_detail=item_detail)

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation.id,
        generation_kind="memory_card",
        scene_key="memory_card",
        target_type=target_type,
        target_id=target_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "memory_card",
        "scene_key": "memory_card",
        "target_type": target_type,
        "target_id": target_id,
        "user_id": user_id,
        "relation_id": relation.id,
        "prompt": prompt,
        "output_model": None,
        "temperature": 0.7,
        "max_tokens": 1500,
    }


def prepare_relationship_review(
    db: Session,
    user_id: int,
    relation_id: int,
    description: str,
    context: Optional[str] = None,
) -> dict:
    """流式版「关系复盘」的前处理。

    对应功能设计 六.9 的六项输出：触发点 / 双方真实需求 / 误解发生处 /
    升级冲突的话语 / 降低冲突的有效表达 / 下次可提前使用的表达方式。

    与其它 prepare_* 一样只返回纯数据：请求级 db 会在 StreamingResponse
    开始消费前就被销毁，不能把 ORM 实例带进生成器。
    """
    from app.services import ai_generation_service  # 局部导入，避免模块加载环

    scene = ai_repo.get_scene_by_key(db, "relationship_review")
    if not scene:
        raise ValueError("50001")

    # 输入安全过滤：与其它 AI 入口同一套护栏，命中即不调模型
    input_risk, input_hits = check_input_safety_detail(description)
    if input_risk != "normal":
        safety_repo.log_event(user_id, "relationship_review", "input", input_risk, input_hits)
        raise ValueError("20001")

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

    user_profile_text = _format_profile(user_profile, user_scores, db, user_id)
    partner_profile_text = _format_profile(
        partner_profile, partner_scores, db, partner_id
    )

    rag_chunks = retrieve_chunks(db, description)
    rag_context = build_rag_context(rag_chunks)
    memory_context = get_memory_context(db, user_id, relation_id, query=description)

    user_input = description if not context else f"{description}\n\n## 补充背景\n{context}"

    base_prompt = build_prompt(
        scene_key="relationship_review",
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict_pattern or "未确定",
        user_input=user_input,
        history="",
        rag_context=rag_context,
        memory_context=memory_context,
    )
    prompt = build_structured_stream_prompt(
        base_prompt,
        ReviewOutput,
        content_instruction=(
            "先共情，再按「触发点 / 你真正想要的 / TA 真正想要的 / 误解从哪开始 / "
            "下次可以怎么说」的自然顺序讲清楚，不要罗列字段名"
        ),
        max_content_chars=900,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind="relationship_review",
        scene_key="relationship_review",
        target_type="none",
        target_id=None,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": "relationship_review",
        "scene_key": "relationship_review",
        "target_type": "none",
        "target_id": None,
        "user_id": user_id,
        "relation_id": relation_id,
        "prompt": prompt,
        "output_model": ReviewOutput,
        "temperature": 0.7,
        "max_tokens": 4000,
    }
