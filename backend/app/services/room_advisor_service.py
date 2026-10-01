"""共同调解室 · 军师发言 SSE 流式通道（D-CHANNEL：API 进程内）。

与 worker 链路的分工（设计文档 §四）：
- 军师发言是交互式等待（用户盯着屏幕）→ 走本模块的 API 进程 SSE；
- 摘要压缩 / 结算等非交互任务 → `room_settlement_service` 由 ai-task-worker 跑。

prompt 组装（"收拢上面的对话"的正确做法，§四）：

    固定头（恒定，不随对话膨胀）
      ├─ 风格 skill 提示词（mediation_styles）
      ├─ 事件卡四字段（创建时一次成型）
      └─ 双方画像摘要（复用 profile_repo；禁对方的 private 记忆）
    对话区
      ├─ 滚动摘要（room.advisor_summary，worker 压缩）
      └─ 自上次军师发言以来的新消息原文（有界窗口）

全文只落库（room_message 本身就是全文），**永不整体进 prompt**。

写回事务边界（新增，§九）：军师消息 INSERT + 房间状态推进 + pending 释放
= 同一个 commit；写回前在校验事务里复核 pending 仍在（token 单次有效，
并发双流只允许第一份写回生效）。
"""

import logging
import re
from typing import Iterator, List, Optional, Tuple

from sqlalchemy.orm import Session

from app.models.ai import AiMemory
from app.models.couple_relation import CoupleRelation
from app.models.mediation_room import MediationRoom, RoomMessage
from app.repositories import mediation_room_repo as room_repo
from app.services.party_labels import NEUTRAL_A, NEUTRAL_B, party_labels
from app.repositories import profile_repo
from app.services import mediation_styles
from app.services.lc_prompt_builder import build_chat_messages
from app.services.llm_client import AI_QUOTA_EXHAUSTED_CODE, error_payload
from app.services.memory_retrieval import visibility_filter
from app.services.sse import stream_with_heartbeat

logger = logging.getLogger("couple.room.advisor")

#: 场景标识（进 llm_client 的 scene 参数，用于日志/限流归因）
SCENE_KEY = "mediation_room"

#: 写回会话工厂。生产用 SessionLocal；测试钩子可替换（同 ai_task_service 模式）。
_SESSION_FACTORY = None


def _session_factory():
    global _SESSION_FACTORY
    if _SESSION_FACTORY is None:
        from app.core.database import SessionLocal
        _SESSION_FACTORY = SessionLocal
    return _SESSION_FACTORY


def use_session_factory(factory) -> None:
    """测试钩子：替换写回/清理用的会话工厂。生产路径不调用。"""
    global _SESSION_FACTORY
    _SESSION_FACTORY = factory

#: 原文窗口上限：超过即触发 worker 压缩（summary_pending）
WINDOW_HARD_LIMIT = 60

#: 窗口消息的字符预算（超出截断最旧的部分）
WINDOW_CHAR_BUDGET = 8000

_SYSTEM_TEMPLATE_HEAD = """你是情侣双人调解室里的「军师」——房间里有{label_a}、{label_b}和你三个角色，
你只在你被召唤时发言，发言对象是**双方**（不是某一个人的私聊军师）。
回复必须是纯文本：禁止使用任何 Markdown 语法（#、*、`、~~ 等），
标题与要点用自然段或「一/二/三」「- 」开头表达即可。

## 本次调解事件卡（创建时双方已确认）
- 事件名称：{event_name}
- 发生时间：{event_time}
- 起因：{cause}
- 经过：{process}
- 现状：{current}

## 军师风格（本次调解采用的专家方法）
{style_prompt}

## 双方画像摘要
[user_a]
{user_profile}
[user_b]
{partner_profile}
{conflict_pattern}"""


def build_messages(db: Session, room: MediationRoom, relation: CoupleRelation) -> List[dict]:
    """组装 prompt：固定头 + 滚动摘要 + 新消息窗口。"""
    style = mediation_styles.get_style(room.style_key)
    profile_a = _format_profile(db, relation.user_a_id)
    profile_b = _format_profile(db, relation.user_b_id)

    summary_text = (room.advisor_summary or "").strip()
    memory_text = _format_memories(db, room)
    window_msgs = _dialog_window(db, room)
    labels = party_labels(db, relation)

    system_template = _SYSTEM_TEMPLATE_HEAD.format(
        event_name=room.name,
        event_time=room.event_time,
        cause=room.cause_text,
        process=room.process_text,
        current=room.current_text,
        style_prompt=style["prompt"],
        user_profile=profile_a,
        partner_profile=profile_b,
        conflict_pattern="",
        label_a=labels["user_a"],
        label_b=labels["user_b"],
    )

    window_text = _render_window(window_msgs, labels)
    user_input = (
        "双方正在房间里继续沟通。请以军师身份，基于上面的风格方法与事件卡，"
        "对当前局面给出你的回应。\n\n" + window_text
    )

    return build_chat_messages(
        scene_key=SCENE_KEY,
        user_profile="",
        partner_profile="",
        conflict_pattern="",
        user_input=user_input,
        history="",
        rag_context="",
        memory_context=(
            "## 此前对话滚动摘要（更早的轮次已压缩）\n" + summary_text
            if summary_text
            else ""
        )
        + ("\n\n" if summary_text and memory_text else "")
        + (
            "## 军师记忆（双方可见部分）\n" + memory_text
            if memory_text
            else ""
        ),
        mode="stream",
        system_template=system_template,
    )


def stream_reply_events(
    db: Session, room_id: int, token: str
) -> Iterator[dict]:
    """军师发言 SSE 事件流：meta → thinking* / delta* → done | error。

    事件协议与聊天流一致（thinking 面板 / delta 正文，不可混）。
    全部增量收集完毕后走写回事务；任何异常路径都必须释放 pending。
    """
    import threading

    cancel_event = threading.Event()
    accumulated: List[Tuple[str, str]] = []
    #: 保存异常本身而非类型名——额度/欠费类要据此给出可操作文案
    stream_error: Optional[BaseException] = None

    # token 校验在请求级 db 上完成；生成用独立会话（写回事务边界要求）
    pending = room_repo.get_pending(db, room_id)
    if pending is None or pending.token != token:
        yield {"event": "error", "data": {"code": 61003, "message": "召唤已失效，请重新@军师"}}
        return

    yield {"event": "meta", "data": {"room_id": room_id, "scene": SCENE_KEY}}

    try:
        for item in stream_with_heartbeat(
            _messages_for_stream(db, room_id),
            SCENE_KEY,
            cancel_event=cancel_event,
            max_tokens=1600,
        ):
            if item is None:
                yield {"comment": "keep-alive"}
                continue
            kind, text = item
            accumulated.append((kind, text))
            if kind == "thinking":
                yield {"event": "thinking", "data": {"delta": text}}
            else:
                yield {"event": "delta", "data": {"delta": text}}
    except Exception as exc:  # noqa: BLE001 —— 流中断：不落库，释放 pending
        logger.warning("[ROOM] 军师流式失败 room=%s: %s", room_id, exc)
        stream_error = exc
    finally:
        cancel_event.set()

    if stream_error is not None:
        _release_pending_standalone(room_id)
        payload = error_payload(stream_error)
        # 额度/欠费不算"军师开小差"——原样透出可操作文案；其余失败维持房间
        # 语境的说法，不把内部异常暴露给用户。
        if payload["code"] != AI_QUOTA_EXHAUSTED_CODE:
            payload = {"code": 50000, "message": "军师开小差了，请重新@军师"}
        yield {"event": "error", "data": payload}
        return

    thinking = "".join(t for k, t in accumulated if k == "thinking").strip() or None
    content = "".join(t for k, t in accumulated if k == "content").strip()
    if not content:
        _release_pending_standalone(room_id)
        yield {"event": "error", "data": {"code": 50000, "message": "军师没有给出回应，请重新@军师"}}
        return

    written = _write_back(db, room_id, content=content, thinking=thinking)
    if not written:
        yield {"event": "error", "data": {"code": 61003, "message": "召唤已失效，回应未保存"}}
        return

    yield {"event": "done", "data": {"round_no": written.round_no}}


def _messages_for_stream(db: Session, room_id: int) -> List[dict]:
    """请求级 db 上组装 prompt（流式消费前完成，与会话生命周期解耦）。"""
    room = room_repo.get_room(db, room_id)
    if room is None:
        raise ValueError("room_not_found")
    relation = (
        db.query(CoupleRelation).filter(CoupleRelation.id == room.relation_id).first()
    )
    if relation is None:
        raise ValueError("room_not_found")
    return build_messages(db, room, relation)


def _write_back(db: Session, room_id: int, *, content: str, thinking: Optional[str]):
    """新增写回事务边界（§九）：消息 INSERT + 状态推进 + pending 释放同一 commit。

    用**独立会话**（SessionLocal）而不是请求级 db：流式期间请求级事务一直
    开着读视图，写回必须看到最新行（加锁读），且不能把整个 HTTP 流的时长
    拖进一个数据库事务。

    事务内复核 pending 仍在（token 单次有效）：并发双流时第二份写回被丢弃。
    """
    own = _session_factory()()
    try:
        room = room_repo.get_room_locked(own, room_id)
        if room is None:
            return None
        pending = room_repo.get_pending(own, room_id)
        if pending is None:
            own.rollback()
            return None

        msg = room_repo.add_message(
            own,
            room_id,
            sender_type="advisor",
            content=_strip_markdown(content),
            thinking=thinking,
            risk_level="low",
            round_no=int(room.round_no or 0) + 1,
        )
        room_repo.advance_after_advisor(own, room, msg.id)
        room_repo.delete_pending(own, room_id)

        # 窗口超阈值 → 入队 worker 压缩（§四：军师历史发言超阈值并入滚动摘要）
        window_count = room_repo.count_messages_since(
            own, room_id, int(room.summary_upto_message_id or 0)
        )
        if window_count > WINDOW_HARD_LIMIT:
            room.summary_pending = True

        own.commit()
        return msg
    except Exception:  # noqa: BLE001
        own.rollback()
        logger.exception("[ROOM] 军师写回事务失败 room=%s", room_id)
        return None
    finally:
        own.close()


def _release_pending_standalone(room_id: int) -> None:
    """失败路径释放 pending（独立会话/commit，绝不留在长事务里）。"""
    db = _session_factory()()
    try:
        room_repo.delete_pending(db, room_id)
        db.commit()
    except Exception:  # noqa: BLE001
        db.rollback()
        logger.warning("[ROOM] 释放 pending 失败 room=%s", room_id, exc_info=True)
    finally:
        db.close()


# --------------------------------------------------------------------------- #
# prompt 素材
# --------------------------------------------------------------------------- #

def _dialog_window(db: Session, room: MediationRoom) -> List[RoomMessage]:
    """自上次军师发言以来的新消息原文（有界）。"""
    room_msg = (
        db.query(RoomMessage)
        .filter(
            RoomMessage.room_id == room.id,
            RoomMessage.sender_type == "advisor",
        )
        .order_by(RoomMessage.id.desc())
        .first()
    )
    boundary = max(
        int(room.summary_upto_message_id or 0),
        int(room_msg.id) if room_msg is not None else 0,
    )
    msgs = room_repo.get_messages(db, room.id, after_id=boundary, limit=WINDOW_HARD_LIMIT)
    return _fit_budget(msgs)


#: 军师消息只允许纯文本：剥掉 Markdown 符号（09-28 用户要求，写回前兜底）
_MD_HEADING_RE = re.compile(r"^#{1,6}\s*", re.MULTILINE)
_MD_EMPHASIS_RE = re.compile(r"\*\*|__|~~|`")
_MD_LONE_RE = re.compile(r"\*|#")


def _strip_markdown(text: str) -> str:
    """剥掉军师回复里的 Markdown 符号。prompt 禁令不可靠，这里确定性兜底。"""
    if not text:
        return text or ""
    text = _MD_HEADING_RE.sub("", text)
    text = _MD_EMPHASIS_RE.sub("", text)
    return _MD_LONE_RE.sub("", text)


def _fit_budget(msgs: List[RoomMessage]) -> List[RoomMessage]:
    """字符预算裁剪：从最旧的开始丢，保住最近的上下文。"""
    total = 0
    cutoff = 0
    for i, m in enumerate(msgs):
        total += len(m.content or "")
        if total > WINDOW_CHAR_BUDGET:
            cutoff = i + 1
    return msgs[cutoff:] if cutoff else msgs


def _render_window(msgs: List[RoomMessage], labels: Optional[dict] = None) -> str:
    """窗口消息渲染。labels 为 {"user_a": 称呼, "user_b": 称呼}；不传时用中性称呼。"""
    labels = labels or {"user_a": NEUTRAL_A, "user_b": NEUTRAL_B}
    lines = []
    for m in msgs:
        who = {
            "user_a": labels["user_a"],
            "user_b": labels["user_b"],
            "advisor": "军师",
        }.get(m.sender_type, m.sender_type)
        prefix = "" if m.sender_type == "advisor" else ""
        lines.append("[%s] %s" % (who, m.content))
    if not lines:
        lines.append("(双方还没有发言，请基于事件卡开启调解，先邀请双方各自把感受说完。)")
    return "\n".join(lines)


def _format_profile(db: Session, user_id: int) -> str:
    """画像摘要（复用 profile_repo；拿不到就明说，不编造）。"""
    profile = profile_repo.get_latest_profile(db, user_id)
    if profile is None:
        return "（暂无画像）"
    scores = profile_repo.get_dimension_scores(db, profile.id)
    parts = []
    if profile.summary:
        parts.append("概述：" + profile.summary.strip())
    if scores:
        dims = "；".join(
            "%s %.0f分" % (s.dimension_key, s.score) for s in scores[:8]
        )
        parts.append("维度：" + dims)
    return "\n".join(parts) if parts else "（暂无画像）"


def _format_memories(db: Session, room: MediationRoom) -> str:
    """军师记忆（couple visibility 过滤是硬红线：绝不含对方 private）。

    MVP 用 SQL 近因召回（recent），不走向量检索——房间场景重点是「事件卡 +
    当下对话」，记忆只做轻量背景。P1 再接 AdvisorContext 全链路。
    """
    try:
        rows = (
            db.query(AiMemory)
            .filter(
                AiMemory.relation_id == room.relation_id,
                visibility_filter(_any_member_id(db, room), room.relation_id),
                AiMemory.status == "active",
            )
            .order_by(AiMemory.created_at.desc())
            .limit(8)
            .all()
        )
    except Exception:  # noqa: BLE001 —— 记忆召回失败不挡调解主链路
        logger.warning("[ROOM] 记忆召回失败 room=%s", room.id, exc_info=True)
        return ""
    return "\n".join("- " + (r.memory_text or "").strip() for r in rows if r.memory_text)


def _any_member_id(db: Session, room: MediationRoom) -> int:
    """visibility_filter 需要 viewer user_id（其 private 记忆可见）。
    房间双方都能看彼此的 couple 可见记忆，viewer 取创建者即可。"""
    return room.creator_user_id
