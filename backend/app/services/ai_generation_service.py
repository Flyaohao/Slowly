"""单次触发型 AI 生成：持久化 + 可中断流式编排。

## 适用范围

「AI 军师」这种多轮对话走 `ai_service.stream_chat_events` + `ai_chat_message`；
而**单次触发**的分析类能力——解读一封信、改写一封信、生成回信、改写表达——
走这里，结果落 `ai_generation`。

## 一次生成的生命周期

```
端点（请求级 db）
  └─ begin()      占位 status=streaming，登记取消信号，拿到 generation_id
  └─ 返回 StreamingResponse
       └─ stream_generation_events()             ← 请求级 db 已销毁，只能自己开会话
            ├─ meta 帧（带 generation_id，客户端据此取消）
            ├─ thinking 帧 × N   ← 推理过程，喂「深度思考」面板
            ├─ delta 帧 × N      ← 打字机正文（分隔符之前的部分）
            ├─ notice 帧         ← 正文完了、正在整理结构化结果
            ├─ result 帧         ← 结构化字段
            └─ done 帧
       结束路径（四种，都要落库）
            ├─ 正常完成           → status=done
            ├─ 用户点「停止生成」  → status=interrupted，**保留已生成正文**
            ├─ 客户端断开连接      → status=interrupted，保留已生成正文
            └─ 模型侧失败          → status=failed
```

## 为什么中断也要落库

用户按停的那一刻，屏幕上已经有半段解读了。这段文字对用户仍然有价值，
下一次进详情页应当还能看到；直接丢弃等于让用户白等一次。所以中断路径
和正常路径共用同一个 upsert，只是 `status` 不同。
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, Iterator, List, Optional, Tuple, Type

from pydantic import BaseModel, ValidationError

from app.core.database import SessionLocal
from app.models.ai_generation import AiGeneration
from app.models.letter import Letter
from app.repositories import ai_generation_repo
from app.services import ai_stream_registry
from app.services.letter_service import is_locked_future
from app.services.llm_client import LlmError, llm  # noqa: F401 —— llm 作常量/回落保留
from app.services.safety_service import check_output_safety_detail, merge_risk_levels
from app.services.sse import stream_with_heartbeat
from app.services.structured_stream import StructuredStreamSplitter

logger = logging.getLogger("couple.ai.generation")

STATUS_STREAMING = "streaming"
STATUS_DONE = "done"
STATUS_INTERRUPTED = "interrupted"
STATUS_FAILED = "failed"

#: 落库的思考过程上限（字符），与 `ai_service._THINKING_MAX_CHARS` 同规则。
#: 推理模型的思考常为正文字数的 5~10 倍，无上限会明显撑大表。
_THINKING_MAX_CHARS = 4000


def trim_thinking(text: str) -> str:
    """裁剪思考过程用于落库，超长时保留开头并标注被截断。

    保留开头而非结尾：思考的开头是「怎么理解这个问题」，信息密度高于末尾的复述。
    """
    text = (text or "").strip()
    if len(text) <= _THINKING_MAX_CHARS:
        return text
    return text[:_THINKING_MAX_CHARS] + "\n…（思考过程过长，已截断）"


def to_payload(row: AiGeneration) -> Dict[str, Any]:
    """ORM 行 → 客户端契约。

    `result` 帧与回读端点共用这一份形状，客户端只需要一套 DTO。
    """
    return {
        "generation_id": row.id,
        "generation_kind": row.generation_kind,
        "target_type": row.target_type,
        "target_id": row.target_id,
        "scene_key": row.scene_key,
        "status": row.status,
        "content": row.content or "",
        "thinking": row.thinking or "",
        "structured_output": row.structured_output or {},
        "risk_level": row.risk_level or "normal",
        "created_at": row.created_at,
        "updated_at": row.updated_at,
    }


def get_saved(
    db,
    user_id: int,
    generation_kind: str,
    target_type: str = "none",
    target_id: Optional[int] = None,
) -> Optional[Dict[str, Any]]:
    """回读已保存的生成结果。没有则返回 `None`（客户端据此决定要不要调模型）。

    契约 §2.5-2 解锁门（审查 MEDIUM-3 评估结论：**补门**）：本批发布之前
    落库的信件解读/改写/回信建议，其 `content` / `structured_output` 派生
    自信件原文——目标信是接收方视角的未解锁 future 时拒绝回读，
    与 detail 同语义同错误码（`ValueError("60002")`，由 API 层翻译）。
    """
    row = ai_generation_repo.get_generation(db, user_id, generation_kind, target_type, target_id)
    if row is None:
        return None
    if row.target_type == "letter" and row.target_id:
        # 读**含软删行**的原始行：锁只看 letter_type + unlock_time，
        # 发件方软删信件不应成为收件方解锁前回读派生内容的缺口。
        letter = db.query(Letter).filter(Letter.id == row.target_id).first()
        if letter is not None and is_locked_future(letter, user_id):
            raise ValueError("60002")
    return to_payload(row)


def begin(
    db,
    *,
    user_id: int,
    relation_id: int,
    generation_kind: str,
    scene_key: str,
    target_type: str = "none",
    target_id: Optional[int] = None,
) -> Tuple[int, threading.Event]:
    """占位 + 登记取消信号，返回 `(generation_id, cancel_event)`。

    必须在**请求级 db** 还活着的时候调用：它决定了 `meta` 帧里的 generation_id，
    而客户端要靠这个 id 才能调用取消端点。先落一条 `streaming` 也顺带保证
    「用户点完立刻退出页面」这种极端情况下，记录里至少有一条痕迹。
    """
    # v5.0 D4 配置预检必须先于落库（2026-10-01 补充）：
    # 各 prepare_* 都在 begin() 之后才 build_chat_client，未配置用户会先留下一条
    # status=streaming 的空生成记录、再抛 30010 —— 每试一次就攒一条垃圾行。
    # 在这里统一前置，一处覆盖所有走 ai_generation 的流式端点。
    # 延迟导入理由同 llm_client：本模块与 user_ai_config_service 互相引用。
    from app.services import user_ai_config_service as _uaicfg

    _uaicfg.resolve(db, user_id)

    row = ai_generation_repo.upsert_generation(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind=generation_kind,
        scene_key=scene_key,
        target_type=target_type,
        target_id=target_id,
        status=STATUS_STREAMING,
        content="",
        thinking=None,
        structured_output=None,
        risk_level=None,
        model=None,
    )
    db.commit()
    return row.id, ai_stream_registry.register(row.id)


def cancel(generation_id: int) -> bool:
    """请求取消一次正在进行的生成。返回 False 表示它已经结束了。"""
    return ai_stream_registry.cancel(generation_id)


def _notify_saved(
    prepared: Dict[str, Any],
    *,
    status: str,
    content: str,
    structured: Optional[Dict[str, Any]],
    risk_level: Optional[str],
) -> None:
    """落库之后回调调用方，让它把结果镜像到自己的表（可选钩子）。

    为什么需要：复盘类结果的**权威落点**不是 `ai_generation`（那是覆盖式的最新
    一次），而是调用方自己的追加表（整改 §8.7）。但流式引擎只认 `ai_generation`，
    所以引擎在四种结束路径落库之后统一叫一次 `prepared["on_saved"]`，
    由调用方决定镜像到哪——引擎不需要知道复盘表的存在。

    失败永远不影响已经推给用户的内容：与 `_save` 同策略，异常只记日志。
    """
    callback = prepared.get("on_saved")
    if not callable(callback):
        return
    try:
        callback(
            status=status,
            content=content,
            structured=structured,
            risk_level=risk_level,
        )
    except Exception:
        logger.exception(
            "[AI] 生成结果镜像失败 kind=%s", prepared.get("generation_kind")
        )


def _save(**kwargs: Any) -> None:
    """用独立会话落库，异常只记日志。

    响应体阶段请求级 db 早已销毁，只能自开会话；而这里任何异常都不该
    影响已经推给用户的内容，所以全程吞掉。
    """
    db = SessionLocal()
    try:
        ai_generation_repo.upsert_generation(db, **kwargs)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception(
            "[AI] 生成结果落库失败 kind=%s target=%s",
            kwargs.get("generation_kind"),
            kwargs.get("target_id"),
        )
    finally:
        # 显式归还连接。这个会话不属于任何请求（请求级 db 早已随响应销毁），
        # 没有框架替它收尾；只依赖 GC 的话，连接要等下一次回收才回池。
        # 每流式生成一次就走一次 `_save`，持续不归还等于把连接池慢慢漏干。
        #
        # 2026-09-27：这个漏点在 test_viewpoint_analysis 里暴露成了「进程退出卡死」
        # ——测试基座的 DROP DATABASE 被两根未归还的连接堵住（见该文件注释）。
        db.close()


def _validate_structured(
    payload: Optional[Dict[str, Any]],
    output_model: Optional[Type[BaseModel]],
    generation_kind: str,
) -> Optional[Dict[str, Any]]:
    """用 Pydantic 模型校验分隔符后解析出来的 JSON。

    校验不过不是致命错误：正文已经推给用户了，结构化字段只是锦上添花。
    宁可不显示卡片，也不要因为模型少填一个字段就让整次生成看起来失败。
    `output_model` 为 None 表示这类生成没有结构化字段（如画像报告的
    纯 Markdown 长文），直接返回 None。
    """
    if payload is None or output_model is None:
        return None
    try:
        return output_model.model_validate(payload).model_dump(mode="json")
    except ValidationError as exc:
        logger.warning("[AI] 结构化字段校验未通过 kind=%s: %s", generation_kind, exc)
        return None


def _apply_finalizer(
    prepared: Dict[str, Any], structured: Optional[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """让调用方在结构化结果落库/下发之前做一次归一化。

    量表分析用它把「模型可能随口编的维度分数」覆盖回系统算出的分数，
    并把模型漏掉的维度补齐。没有这个钩子，那类修正只能写进 Prompt 里
    求模型照办——而模型是会改数字的。

    归一化本身失败不当失败处理：正文已经推给用户了，顶多少一层修正。
    """
    if not structured:
        return structured
    finalizer = prepared.get("finalize_structured")
    if not callable(finalizer):
        return structured
    try:
        return finalizer(structured) or structured
    except Exception:
        logger.exception(
            "[AI] 结构化结果归一化失败 kind=%s", prepared.get("generation_kind")
        )
        return structured


def _resolve_risk(content: str, structured: Optional[Dict[str, Any]]) -> str:
    """风险等级取「模型自评」与「词库检测」中的较高者。

    模型自评可能漏判（它倾向于觉得自己输出没问题），词库可能误报，
    两者取高是安全侧的保守选择，与 `ai_service.chat` 的处理一致。
    """
    detected, _hits = check_output_safety_detail(content or "")
    declared = ""
    if structured:
        value = structured.get("risk_level")
        if isinstance(value, str):
            declared = value
    return merge_risk_levels(detected, declared) if declared else detected


def stream_generation_events(prepared: Dict[str, Any]) -> Iterator[Dict[str, Any]]:
    """把一次单次触发型生成拆成 SSE 事件序列。

    `prepared` 由调用方（如 `letter_ai_service.prepare_understand_letter`）
    构造，必须包含：

    | 键 | 说明 |
    | --- | --- |
    | `generation_id` / `cancel_event` | `begin()` 的返回值 |
    | `generation_kind` / `scene_key` / `target_type` / `target_id` | 落库定位信息 |
    | `user_id` / `relation_id` | 落库外键 |
    | `prompt` | 已含结构化输出要求（用 `prompt_builder.build_structured_stream_prompt` 构造） |
    | `output_model` | 用于校验结构化结果的 Pydantic 类 |
    | `temperature` / `max_tokens` | 可选 |
    """
    generation_id: int = prepared["generation_id"]
    cancel_event: threading.Event = prepared["cancel_event"]
    generation_kind: str = prepared["generation_kind"]
    scene_key: str = prepared["scene_key"]
    # output_model 可省：纯文本流式（画像报告）没有结构化字段
    output_model = prepared.get("output_model")
    save_kwargs = {
        "user_id": prepared["user_id"],
        "relation_id": prepared["relation_id"],
        "generation_kind": generation_kind,
        "scene_key": scene_key,
        "target_type": prepared.get("target_type", "none"),
        "target_id": prepared.get("target_id"),
    }

    yield {
        "event": "meta",
        "data": {
            "generation_id": generation_id,
            "generation_kind": generation_kind,
            "target_type": prepared.get("target_type", "none"),
            "target_id": prepared.get("target_id"),
            "scene_key": scene_key,
        },
    }

    splitter = StructuredStreamSplitter()
    thinking_parts: List[str] = []
    structuring_notified = False
    completed = False
    # v5.0 D1：按触发用户解析的客户端（prepare_* 阶段塞入）；缺省回落全局单例
    active_client = prepared.get("client") or llm

    try:
        for item in stream_with_heartbeat(
            [{"role": "system", "content": prepared["prompt"]}],
            scene_key,
            temperature=prepared.get("temperature", 0.7),
            max_tokens=prepared.get("max_tokens", 2000),
            cancel_event=cancel_event,
            client=active_client,
        ):
            if item is None:
                # 兜底保活：模型连思考都不吐时，用注释帧证明连接还活着
                yield {"comment": "keep-alive"}
                continue

            kind, text = item
            if kind == llm.KIND_THINKING:
                thinking_parts.append(text)
                yield {"event": "thinking", "data": {"content": text}}
                continue

            visible = splitter.feed(text)
            if visible:
                yield {"event": "delta", "data": {"content": visible}}
            if splitter.phase == "structured" and not structuring_notified:
                # 正文已经说完，接下来是纯 JSON 生成期。这段时间用户看不见东西，
                # 明确告诉他「正在整理」比让他盯着光标强。
                structuring_notified = True
                yield {"event": "notice", "data": {"stage": "structuring"}}

        # ---------------------------------------------------------------- #
        # 正常收尾
        # ---------------------------------------------------------------- #
        # 走到这里有两种可能：模型把话说完了，或者用户点了「停止生成」——
        # 后者会让 worker 安静退出，循环同样正常结束，因此必须靠信号位区分。
        cancelled = cancel_event.is_set()
        content, structured_raw = splitter.finish()
        # 落库与终稿都去掉首尾空白：delta 中间的换行是打字机效果的一部分，
        # 但正文与分隔符之间的那段空行不该带进数据库和卡片。
        content = content.strip()
        structured = _validate_structured(structured_raw, output_model, generation_kind)
        structured = _apply_finalizer(prepared, structured)
        if structured_raw and structured is None:
            # 分隔符之后有内容却解析不出来，多半是被 max_tokens 截断在 JSON 中间。
            # 正文仍然可用，所以不当失败处理，只留痕方便定位。
            logger.warning(
                "[AI] 结构化片段解析失败 kind=%s 长度=%d 片段=%r",
                generation_kind, len(structured_raw), structured_raw[:200],
            )
        risk = _resolve_risk(content, structured)
        thinking = trim_thinking("".join(thinking_parts))

        final_status = STATUS_INTERRUPTED if cancelled else STATUS_DONE
        _save(
            **save_kwargs,
            status=final_status,
            content=content,
            thinking=thinking,
            structured_output=structured,
            risk_level=risk,
            model=getattr(active_client, "model", llm.model),
        )
        _notify_saved(
            prepared,
            status=final_status,
            content=content,
            structured=structured,
            risk_level=risk,
        )
        # 先置位再 yield：万一客户端恰好在 done 帧处断开，
        # finally 里的「未完成兜底落库」就不会把刚写好的 done 覆盖成 interrupted。
        completed = True

        yield {
            "event": "result",
            "data": {
                "generation_id": generation_id,
                "structured_output": structured or {},
                "content": content,
                "risk_level": risk,
            },
        }
        yield {
            "event": "done",
            "data": {
                "generation_id": generation_id,
                "status": STATUS_INTERRUPTED if cancelled else STATUS_DONE,
                "interrupted": cancelled,
                "content": content,
                "thinking": thinking,
                "structured_output": structured or {},
                "risk_level": risk,
            },
        }

    except LlmError as exc:
        logger.error("[AI] 流式生成失败 kind=%s: %s", generation_kind, exc)
        partial = splitter.content_so_far.strip()
        _save(
            **save_kwargs,
            status=STATUS_FAILED,
            content=partial,
            thinking=trim_thinking("".join(thinking_parts)),
            structured_output=None,
            risk_level=None,
            model=None,
        )
        _notify_saved(
            prepared,
            status=STATUS_FAILED,
            content=partial,
            structured=None,
            risk_level=None,
        )
        completed = True
        yield {
            "event": "error",
            "data": {"code": 50000, "message": "AI 服务异常，请稍后重试"},
        }

    finally:
        # 无论怎么结束，都要让后台 worker 停手，否则它会继续把模型的话拉完。
        cancel_event.set()
        ai_stream_registry.unregister(generation_id)
        if not completed:
            # 客户端断开（GeneratorExit）才会走到这里：把已经生成的部分存下来。
            # 这里不能 yield（生成器正在关闭），只能做落库这类纯 IO。
            logger.info("[AI] 生成被中断，保留半成品 generation=%s", generation_id)
            partial = splitter.content_so_far.strip()
            _save(
                **save_kwargs,
                status=STATUS_INTERRUPTED,
                content=partial,
                thinking=trim_thinking("".join(thinking_parts)),
                structured_output=None,
                risk_level=None,
                model=None,
            )
            _notify_saved(
                prepared,
                status=STATUS_INTERRUPTED,
                content=partial,
                structured=None,
                risk_level=None,
            )
