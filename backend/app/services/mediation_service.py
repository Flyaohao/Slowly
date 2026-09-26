import json
import logging
import threading
from typing import Optional, Dict, List, Set, Tuple
from datetime import datetime

from sqlalchemy.orm import Session
from starlette.websockets import WebSocket, WebSocketDisconnect

from app.models.ai import AiChatSession, AiChatMessage
from app.repositories import ai_repo, couple_repo, profile_repo
from app.services.safety_service import check_input_safety_detail, get_safety_response
from app.repositories import safety_repo
from app.services.ai_service import _call_llm, _format_profile
from app.services.prompt_builder import (
    build_mediation_rewrite_prompt,
    build_mediation_summary_prompt,
)
from app.services.notification_service import (
    notify_mediation_invite,
    schedule_notify,
)

logger = logging.getLogger(__name__)

MEDIATION_STATUSES = [
    "inviting", "accepted", "inputting", "rewriting",
    "confirming", "summarizing", "completed",
]

#: 契约 §2.3-7：首页 active_mediation 的真实状态集（状态机从不产生 in_progress）
ACTIVE_MEDIATION_STATUSES = [
    "inviting", "accepted", "inputting", "confirming",
    "rewriting", "summarizing",
]

#: 契约 §8.5-8：同步 LLM 生成（单次 120s 超时）不能阻塞请求。
#: rewriting / summarizing 两个状态直接返回给调用方，真正的生成在守护线程里跑完
#: 再翻状态 + 广播；客户端按状态轮询（§8.5-1）与 WS 状态帧（§2.3-4）获知结果。
ASYNC_GENERATION_STATUSES = {"rewriting", "summarizing"}

#: 契约 §8.5-3：双方独立输入在「明确公开（总结生成）」之前不得互相返回。
#: 这些状态下 GET {id} 的 messages 只回当前用户自己的发言。
MESSAGES_PRIVATE_STATUSES = frozenset({
    "inviting", "accepted", "inputting", "rewriting", "confirming",
})

#: 契约 §8.5-6：已完成的调解可重新查看——completed 不再是「拒绝访问」的理由。
READABLE_STATUSES = frozenset(MEDIATION_STATUSES)

#: 测试开关：置 True 时改写/总结在**当前线程**里跑完（见 [_run_generation]）。
#: 生产路径永远为 False；只由 hermetic 测试显式打开，避免断言靠 sleep 赌调度。
GENERATION_INLINE = False


class ConnectionManager:
    def __init__(self):
        self._connections: Dict[int, List[WebSocket]] = {}
        #: 首个 WS 连接所在事件循环——notification_service.schedule_notify
        #: 从同步请求线程投递通知时要用它（线程池线程没有 current loop）
        self._loop = None

    @property
    def loop(self):
        return self._loop

    async def connect(self, user_id: int, ws: WebSocket):
        import asyncio
        self._loop = asyncio.get_running_loop()
        await ws.accept()
        if user_id not in self._connections:
            self._connections[user_id] = []
        conns = self._connections[user_id]
        if len(conns) >= 2:
            old = conns.pop(0)
            try:
                await old.close()
            except Exception:
                pass
        conns.append(ws)

    def disconnect(self, user_id: int, ws: WebSocket):
        if user_id in self._connections:
            self._connections[user_id] = [
                c for c in self._connections[user_id] if c is not ws
            ]
            if not self._connections[user_id]:
                del self._connections[user_id]

    async def disconnect_user(self, user_id: int, reason: str = "Mode changed"):
        """强制断开用户的所有 WebSocket 连接"""
        conns = self._connections.pop(user_id, [])
        for ws in conns:
            try:
                await ws.close(code=4004, reason=reason)
            except Exception:
                pass

    async def send_to_user(self, user_id: int, message: dict):
        conns = self._connections.get(user_id, [])
        dead = []
        for ws in conns:
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        for ws in dead:
            conns.remove(ws)

    async def broadcast_to_session(self, user_ids: List[int], message: dict):
        for uid in user_ids:
            await self.send_to_user(uid, message)


manager = ConnectionManager()


def start_mediation(db: Session, user_id: int, relation_id: int) -> dict:
    relation = couple_repo.get_relation_by_id(db, relation_id)
    if not relation:
        raise ValueError("50001")
    partner_id = relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
    session = AiChatSession(
        user_id=user_id,
        relation_id=relation_id,
        scene_key="mediation",
        title="双人调解",
        privacy_level="couple",
        session_type="mediation",
        partner_user_id=partner_id,
        mediation_status="inviting",
    )
    db.add(session)
    db.flush()
    db.commit()

    # 契约 §2.3-5：start 成功后必须通知伴侣（此前零调用点，邀请只能靠 WS 偶遇）
    _notify_invite(db, user_id, partner_id, session.id)

    return {
        "session_id": session.id,
        "partner_user_id": partner_id,
        "mediation_status": "inviting",
        # 契约 §2.3-1：start 只有发起方能调，固定 inviter
        "my_role": "inviter",
    }


def _notify_invite(db: Session, inviter_id: int, partner_id: int, session_id: int) -> None:
    """调解邀请通知（WS 实时帧 + 白名单邮件），失败绝不影响 start 主链路。"""
    from app.repositories import user_repo

    inviter_name = "你的伴侣"
    try:
        profile = user_repo.get_profile_by_user_id(db, inviter_id)
        if profile and profile.nickname:
            inviter_name = profile.nickname
    except Exception:  # noqa: BLE001
        pass
    schedule_notify(
        notify_mediation_invite(partner_id, session_id, inviter_name),
        "mediation_invite",
    )


def _my_role(session: AiChatSession, user_id: int) -> str:
    """契约 §2.3-2：服务端按会话行推导身份，导航参数只是展示兜底。"""
    return "inviter" if session.user_id == user_id else "partner"


def _my_confirm_at(session: AiChatSession, user_id: int) -> Optional[datetime]:
    """当前用户在自己那一侧记下的确认时间（NULL = 未确认）。"""
    return (
        session.confirm_inviter_at
        if session.user_id == user_id
        else session.confirm_partner_at
    )


def _set_my_confirm(
    session: AiChatSession, user_id: int, when: Optional[datetime]
) -> None:
    """写/撤自己的确认时间（None = 撤回，用于「需要修改」）。"""
    if session.user_id == user_id:
        session.confirm_inviter_at = when
    else:
        session.confirm_partner_at = when


def _my_rewrite_text(db: Session, session: AiChatSession, user_id: int) -> str:
    """最新一份改写里**当前用户那一侧**的文本（空串 = 还没有/缺这一侧）。

    以身份取数（inviter 侧 = rewrite_a），与 [get_status] 的 my_rewrite 同源。
    """
    messages = ai_repo.get_messages_by_session(db, session.id)
    for m in reversed(messages):
        so = m.structured_output or {}
        if m.role == "assistant" and (so.get("rewrite_a") or so.get("rewrite_b")):
            key = "rewrite_a" if session.user_id == user_id else "rewrite_b"
            return so.get(key) or ""
    return ""


def _both_confirmed(session: AiChatSession) -> bool:
    """契约 §8.5-5：双方各自确认过才谈得上生成总结。

    partner_user_id 为空的脏数据永远算「没齐」——宁可停在确认页，也不放行一个
    只有一方点过的总结。
    """
    return (
        session.confirm_inviter_at is not None
        and session.confirm_partner_at is not None
    )


def _broadcast_status(session: AiChatSession) -> None:
    """契约 §2.3-4：状态变更时向会话双方 WS 推状态帧（动作本身走 REST）。"""
    frame = {
        "type": "mediation_status",
        "session_id": session.id,
        "status": session.mediation_status,
    }
    members = [uid for uid in (session.user_id, session.partner_user_id) if uid]
    schedule_notify(
        manager.broadcast_to_session(members, frame),
        "mediation_status",
    )


def accept_mediation(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.mediation_status == "completed":
        raise ValueError("50003")
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    session.mediation_status = "inputting"
    db.commit()
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": "inputting"}


def reject_mediation(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    session.mediation_status = "completed"
    db.commit()
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": "completed"}


def submit_input(db: Session, session_id: int, user_id: int, content: str) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.mediation_status not in ("inputting", "accepted"):
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    safety_risk, safety_hits = check_input_safety_detail(content)
    if safety_risk != "normal":
        safety_repo.log_event(user_id, "mediation", "input", safety_risk, safety_hits)
        safety_resp = get_safety_response(safety_risk)
        return {"blocked": True, "risk_level": safety_risk, "safety_response": safety_resp}

    # 契约 §2.4-1：user 消息必须带作者，按人判定/按作者分组都靠它
    ai_repo.create_message(db, session_id, "user", content, user_id=user_id)
    db.commit()

    # 契约 §2.4-2：「双方已提交」按 distinct user 判定——同一个人提交两次不算；
    # user_id 为 NULL 的旧数据不参与判定（无从归属）。
    messages = ai_repo.get_messages_by_session(db, session_id)
    submitted = {
        m.user_id for m in messages
        if m.role == "user" and m.user_id is not None
    }
    both_submitted = (
        session.user_id in submitted and session.partner_user_id in submitted
    )

    if both_submitted and session.mediation_status in ("inputting", "accepted"):
        # 契约 §8.5-2：第一方提交后进**等待态**（这里就是 inputting），
        # 双方都提交后才进改写；§8.5-8：改写生成本身丢到后台线程，
        # 请求立刻返回 rewriting，FE 按状态轮询/收 WS 帧。
        session.mediation_status = "rewriting"
        db.commit()
        _broadcast_status(session)
        _spawn_rewrite(session_id)
        # 这里**不能**再补一次状态广播：后台线程可能已经翻到 confirming，
        # 补发的 rewriting 帧会晚于 confirming 到达，FE 的状态机会被推回去。
        return {
            "session_id": session_id,
            "mediation_status": "rewriting",
            "partner_submitted": session.partner_user_id in submitted,
        }

    _broadcast_status(session)
    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "partner_submitted": session.partner_user_id in submitted,
    }


def confirm_rewrite(
    db: Session,
    session_id: int,
    user_id: int,
    confirmed: bool,
    supplement: Optional[str] = None,
) -> dict:
    """契约 §8.5-5：确认是**按人记状态**的，「双方确认后才生成总结」。

    - 确认准确 → 只在**自己那一侧**记下确认时间；对方没确认就停在 confirming
      （等待态），双方齐了才进 summarizing 并在后台线程生成总结。
    - 需要修改 → 补充说明落库，**只重生成自己这一侧**的改写，对方那一侧的内容
      与确认状态都不动（此前双方一起重写，等于替对方改了稿）。
    """
    session = _get_session(db, session_id, user_id)
    if session.mediation_status != "confirming":
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    # 补充说明作为该用户的补充发言落库（按作者分组的改写输入，只喂自己这一侧）
    if supplement and supplement.strip():
        ai_repo.create_message(db, session_id, "user", supplement.strip(), user_id=user_id)
        db.commit()

    if not confirmed:
        # §8.5-5：撤回确认再重生成——**两侧都撤**。改写是对「当前这份稿」的确认，
        # 只要有一侧重生成，对方此前对旧稿的确认就失效了；只撤自己那一侧的话，
        # 「A 确认 → B 要修改 → B 确认」会立刻放行一个 A 从没看过的新版本总结。
        _set_my_confirm(session, session.user_id, None)
        _set_my_confirm(session, session.partner_user_id, None)
        db.commit()
        _regenerate_my_rewrite(db, session, user_id)
        session = ai_repo.get_session_by_id(db, session_id)
        _broadcast_status(session)
        return {
            "session_id": session_id,
            "mediation_status": session.mediation_status,
            "my_confirmed": False,
            "partner_confirmed": False,
        }

    # 审查 H2 的**按人版本**：自己那一侧没有改写文本时，绝不记下确认——
    # 否则两级确认齐了就出一个「整场没有改写」的总结。缺失时补生成一次，
    # 补不出来就明确 50000，停在 confirming。
    if not _my_rewrite_text(db, session, user_id):
        _regenerate_my_rewrite(db, session, user_id)
        session = ai_repo.get_session_by_id(db, session_id)

    _set_my_confirm(session, user_id, datetime.now())
    db.commit()

    if not _both_confirmed(session):
        # 对方还没确认：停在 confirming，不生成总结——§8.5-5 的硬门。
        _broadcast_status(session)
        return {
            "session_id": session_id,
            "mediation_status": session.mediation_status,
            "my_confirmed": True,
            "partner_confirmed": False,
        }

    # 双方确认齐了 → summarizing（后台生成总结，§8.5-8）
    session.mediation_status = "summarizing"
    db.commit()
    _broadcast_status(session)
    _spawn_summary(session_id)
    # 状态以函数内这一刻为准：后台线程可能已经完成并翻到 confirming。
    # 不重读 DB（那会读到后台线程的结果，返回值与刚广播的帧不一致），
    # FE 靠状态帧收敛。
    return {
        "session_id": session_id,
        "mediation_status": "summarizing",
        "my_confirmed": True,
        "partner_confirmed": True,
    }


def _partner_confirmed(session: AiChatSession, user_id: int) -> bool:
    """对方是否已确认（用于状态展示；与「我是否确认」对称，不含己方）。"""
    if session.user_id == user_id:
        return session.confirm_partner_at is not None
    return session.confirm_inviter_at is not None


def _spawn_rewrite(session_id: int) -> None:
    """后台线程生成改写（契约 §8.5-8：不阻塞请求）。

    生成完把状态翻到 confirming；失败也翻 confirming（FE 确认页要有内容可确认，
    失败时「需要修改」会只重生成自己那一侧），并把状态帧广播出去。
    """
    def _worker(bg) -> None:
        try:
            process_rewrite(bg, session_id)
        except Exception:  # noqa: BLE001
            logger.warning("调解改写生成失败 session=%s", session_id, exc_info=True)
        finally:
            try:
                s = ai_repo.get_session_by_id(bg, session_id)
                if s is not None and s.mediation_status in ASYNC_GENERATION_STATUSES:
                    s.mediation_status = "confirming"
                    bg.commit()
                    _broadcast_status(s)
            except Exception:  # noqa: BLE001
                logger.warning("调解改写状态回填失败 session=%s", session_id, exc_info=True)

    _run_generation(_worker, "mediation-rewrite-%s" % session_id)


def _spawn_summary(session_id: int) -> None:
    """后台线程生成总结（契约 §8.5-8）。失败回 confirming，保住已有改写。"""
    def _worker(bg) -> None:
        try:
            generate_summary(bg, session_id)
            s = ai_repo.get_session_by_id(bg, session_id)
            if s is not None and s.mediation_status == "summarizing":
                s.mediation_status = "completed"
                bg.commit()
                _broadcast_status(s)
        except Exception:  # noqa: BLE001
            logger.warning("调解总结生成失败 session=%s", session_id, exc_info=True)
            try:
                s = ai_repo.get_session_by_id(bg, session_id)
                if s is not None and s.mediation_status == "summarizing":
                    s.mediation_status = "confirming"
                    bg.commit()
                    _broadcast_status(s)
            except Exception:  # noqa: BLE001
                logger.warning("调解总结状态回填失败 session=%s", session_id, exc_info=True)

    _run_generation(_worker, "mediation-summary-%s" % session_id)


def _run_generation(worker, thread_name: str) -> None:
    """把一次生成丢进守护线程（测试可切到同步模式）。

    生产路径永远走后台线程：§8.5-8 要求 120s 的同步 LLM 调用不得阻塞请求。
    测试需要**确定性地**观测「生成完成后状态是什么」，所以留一个显式开关
    同步执行——否则断言只能靠 sleep 赌线程调度，正是审查里最容易漏掉的
    假绿（PASS 来自还没跑到的那一步）。
    """
    if GENERATION_INLINE:
        from app.core.database import SessionLocal

        bg = SessionLocal()
        try:
            worker(bg)
        finally:
            bg.close()
        return

    def _entry() -> None:
        from app.core.database import SessionLocal

        bg = SessionLocal()
        try:
            worker(bg)
        finally:
            bg.close()

    threading.Thread(target=_entry, name=thread_name, daemon=True).start()


def _regenerate_my_rewrite(
    db: Session, session: AiChatSession, user_id: int
) -> None:
    """只重生成当前用户那一侧的改写（§8.5-5「需要修改」）。

    实现方式与 [process_rewrite] 共用同一套「按作者分组」的取数，只是把
    **对方那一侧的原样保留**：取上一份改写的对侧文本作为该侧内容，重新写一条
    assistant 消息。这样对方在他自己页面上看到的仍是原稿，不会被悄悄换掉。
    """
    messages = ai_repo.get_messages_by_session(db, session.id)
    prev = next(
        (
            m for m in reversed(messages)
            if m.role == "assistant" and m.structured_output
            and (m.structured_output.get("rewrite_a") or m.structured_output.get("rewrite_b"))
        ),
        None,
    )
    keep_a = (prev.structured_output or {}).get("rewrite_a", "") if prev else ""
    keep_b = (prev.structured_output or {}).get("rewrite_b", "") if prev else ""

    session.mediation_status = "rewriting"
    db.commit()

    try:
        fresh = process_rewrite(db, session.id) or {}
    except Exception:  # noqa: BLE001
        # 审查 H2：改写生成失败绝不静默成功。状态退回 confirming（保住上一份
        # 改写与确认页），并明确抛 50000 让调用方报错——否则会一路确认下去，
        # 最后出现「completed 却整场没有改写」的会话。
        logger.warning(
            "调解改写重生成失败 session=%s user=%s", session.id, user_id, exc_info=True
        )
        current = ai_repo.get_session_by_id(db, session.id)
        if current is not None and current.mediation_status == "rewriting":
            current.mediation_status = "confirming"
            db.commit()
        raise ValueError("50000")

    mine_is_a = session.user_id == user_id
    merged = {
        "rewrite_a": fresh.get("rewrite_a", "") if mine_is_a else keep_a,
        "rewrite_b": (keep_b if mine_is_a else fresh.get("rewrite_b", "")),
        "risk_level": fresh.get("risk_level", "normal"),
    }
    # 合并后的两条改写作为**新的一条** assistant 消息落库：GET {id} 取的是最后
    # 一条含改写的消息，所以对方那侧必须一并带上（否则会被当成消失）。
    ai_repo.create_message(
        db, session.id, "assistant",
        json.dumps(merged, ensure_ascii=False),
        structured_output=merged,
    )
    db.commit()

    session = ai_repo.get_session_by_id(db, session.id)
    session.mediation_status = "confirming"
    db.commit()


def list_mediations(db: Session, user_id: int, role: str = "mine") -> dict:
    """契约 §2.3-3：伴侣侧调解列表（默认 mine）。"""
    sessions = ai_repo.get_mediation_sessions(db, user_id, role)
    items = [
        {
            "session_id": s.id,
            "mediation_status": s.mediation_status,
            "session_type": s.session_type,
            "user_id": s.user_id,
            "partner_user_id": s.partner_user_id,
            "my_role": _my_role(s, user_id),
            "title": s.title,
            "created_at": s.created_at.isoformat() if s.created_at else None,
            "updated_at": s.updated_at.isoformat() if s.updated_at else None,
        }
        for s in sessions
    ]
    return {"total": len(items), "items": items}


def get_status(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    all_messages = ai_repo.get_messages_by_session(db, session_id)
    my_role = _my_role(session, user_id)

    # 契约 §8.5-3：**公开之前**，双方独立输入不得互相返回。
    # messages / partner_rewrite / rewrites 三个出口里，凡是能拼出对方原话的
    # 字段（对方输入、对方改写）在这一段状态区间内一律不回——不是 UI 不渲染，
    # 是响应里根本没有。总结生成（summarizing / completed）后才公开。
    revealed = session.mediation_status in ("summarizing", "completed")
    messages = all_messages if revealed else [
        m for m in all_messages
        if m.user_id is None or m.user_id == user_id
    ]

    # 契约 §2.4-2：对方是否已提交（distinct user，NULL 旧数据不计）
    partner_submitted = any(
        m.role == "user" and m.user_id == session.partner_user_id
        for m in all_messages
    )
    # §8.5-7：**我**是否已提交。断线重进时「我提交过没有」直接决定该显示输入框
    # 还是等待态——只看会话状态判断不出来（我是第一方时状态一直停在 inputting），
    # 只能由服务端按作者给出，客户端不许自己猜。
    my_submitted = any(
        m.role == "user" and m.user_id == user_id for m in all_messages
    )

    # 改写按作者解析：rewrite_a=发起方(inviter) 侧，rewrite_b=参与方(partner) 侧
    rewrite_msg = next(
        (
            m for m in reversed(messages)
            if m.role == "assistant" and m.structured_output
            and (m.structured_output.get("rewrite_a") or m.structured_output.get("rewrite_b"))
        ),
        None,
    )
    my_rewrite = None
    partner_rewrite = None
    rewrites = []
    if rewrite_msg is not None:
        so = rewrite_msg.structured_output
        inviter_text = so.get("rewrite_a") or ""
        partner_text = so.get("rewrite_b") or ""
        inviter_original = _last_input(all_messages, session.user_id)
        partner_original = _last_input(all_messages, session.partner_user_id)
        # §8.5-3：对方那一侧的原话与改写只在公开后才给
        rewrites = [{"author_user_id": session.user_id, "content": inviter_text}]
        if revealed:
            rewrites.append(
                {"author_user_id": session.partner_user_id, "content": partner_text}
            )
        if my_role == "inviter":
            my_rewrite = {"original": inviter_original, "rewritten": inviter_text}
            if revealed:
                partner_rewrite = {"original": partner_original, "rewritten": partner_text}
        else:
            my_rewrite = {"original": partner_original, "rewritten": partner_text}
            if revealed:
                partner_rewrite = {"original": inviter_original, "rewritten": inviter_text}

    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "session_type": session.session_type,
        "user_id": session.user_id,
        "partner_user_id": session.partner_user_id,
        # ---- 以下均为契约 §2.3-2 / §2.4 / §8.5 只增不减的新字段 ----
        "my_role": my_role,
        "partner_submitted": partner_submitted,
        # §8.5-7：我提交过没有（断线重进决定输入框还是等待态）
        "my_submitted": my_submitted,
        # §8.5-5：双方各自的确认状态（断线重进按它恢复步骤）
        "my_confirmed": _my_confirm_at(session, user_id) is not None,
        "partner_confirmed": _partner_confirmed(session, user_id),
        "updated_at": session.updated_at.isoformat() if session.updated_at else None,
        "my_rewrite": my_rewrite,
        "partner_rewrite": partner_rewrite,
        "rewrites": rewrites,
        "messages": [
            _message_payload(m, my_role, revealed) for m in messages
        ],
    }


def _message_payload(
    message: AiChatMessage, my_role: str, revealed: bool
) -> dict:
    """单条消息的响应负载（契约 §8.5-3 的过滤在这一层统一做）。"""
    visible = _visible_structured_output(message.structured_output, my_role, revealed)
    # content 与 structured_output 是同一份内容的两种表示（create_message 里
    # content=json.dumps(structured_output)），过滤了结构体就要同步过滤字符串，
    # 否则对方那一侧还能从 content 里原样读出来。
    content = (
        message.content
        if visible is message.structured_output
        else json.dumps(visible, ensure_ascii=False)
    )
    return {
        "id": message.id,
        "role": message.role,
        "content": content,
        "structured_output": visible,
        "risk_level": message.risk_level,
        "created_at": message.created_at.isoformat() if message.created_at else None,
    }


def _visible_structured_output(
    structured_output: Optional[dict], my_role: str, revealed: bool
) -> Optional[dict]:
    """契约 §8.5-3：未公开前，改写消息里**对方那一侧**的键不出现在响应里。

    只按作者过滤 `messages` 是不够的：assistant 的改写消息把两侧文本同时放在
    `structured_output`（以及同一份 JSON 的 `content` 字符串）里，对方那一侧会
    顺着「只增不减」的旧字段泄露出去——过滤消息作者却不过滤消息内容是白做。
    公开后原样返回，旧字段写什么还是什么。
    """
    if revealed or not structured_output:
        return structured_output
    mine = "rewrite_a" if my_role == "inviter" else "rewrite_b"
    theirs = "rewrite_b" if my_role == "inviter" else "rewrite_a"
    if mine not in structured_output and theirs not in structured_output:
        return structured_output  # 总结等其它消息：与改写无关，不看
    if theirs not in structured_output:
        return structured_output
    return {k: v for k, v in structured_output.items() if k != theirs}


def _last_input(messages: List[AiChatMessage], uid: Optional[int]) -> str:
    if uid is None:
        return ""
    for m in reversed(messages):
        if m.role == "user" and m.user_id == uid:
            return m.content
    return ""


def next_step(db: Session, session_id: int, user_id: int, action: str) -> dict:
    session = _get_session(db, session_id, user_id)
    if action == "end":
        session.mediation_status = "completed"
    elif action == "pause":
        pass
    elif action == "continue":
        session.mediation_status = "inputting"
    else:
        raise ValueError("50005")
    db.commit()
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": session.mediation_status}


def _build_prompt_context(db: Session, session: AiChatSession) -> Tuple[str, str, str]:
    """画像/冲突模式上下文（发起方画像、参与方画像、冲突模式）。

    与 ai_service 同源：_format_profile 是画像注入唯一出口，profile_repo 取数。
    取不到时降级为「未完成问卷」/「未确定」，绝不阻断调解链路。
    """
    inviter_id = session.user_id
    partner_id = session.partner_user_id

    inviter_profile = profile_repo.get_latest_profile(db, inviter_id)
    partner_profile = (
        profile_repo.get_latest_profile(db, partner_id) if partner_id else None
    )
    inviter_scores: Dict[str, float] = {}
    partner_scores: Dict[str, float] = {}
    if inviter_profile:
        inviter_scores = {
            d.dimension_key: d.score
            for d in profile_repo.get_dimension_scores(db, inviter_profile.id)
        }
    if partner_profile:
        partner_scores = {
            d.dimension_key: d.score
            for d in profile_repo.get_dimension_scores(db, partner_profile.id)
        }
    couple_prof = profile_repo.get_latest_couple_profile(db, session.relation_id)
    conflict = (
        couple_prof.conflict_pattern
        if couple_prof and couple_prof.conflict_pattern
        else "未确定"
    )
    return (
        _format_profile(inviter_profile, inviter_scores, db, inviter_id),
        _format_profile(partner_profile, partner_scores, db, partner_id),
        conflict,
    )


def process_rewrite(db: Session, session_id: int) -> dict:
    """生成双方改写（契约 §2.4-3：输入按 user_id 分组，不再按发言顺序拼接）。

    rewrite_a = 发起方(inviter) 侧、rewrite_b = 参与方(partner) 侧——语义由
    「发言顺序的 A/B」改为「身份 A/B」，输出侧以 author_user_id 为准。
    状态机（rewriting → confirming）由调用方负责，本函数只产内容。

    prompt 一律走 `prompt_builder`（代码风格约束：提示词不得硬编码在业务函数里）。
    """
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")

    messages = ai_repo.get_messages_by_session(db, session_id)
    inviter_inputs = [
        m.content for m in messages
        if m.role == "user" and m.user_id == session.user_id
    ]
    partner_inputs = [
        m.content for m in messages
        if m.role == "user"
        and session.partner_user_id is not None
        and m.user_id == session.partner_user_id
    ]
    # 旧数据（user_id 为 NULL）无从归属：保底并入发起方侧，避免历史输入静默丢失
    legacy_inputs = [
        m.content for m in messages if m.role == "user" and m.user_id is None
    ]
    if legacy_inputs:
        inviter_inputs = legacy_inputs + inviter_inputs

    user_profile_text, partner_profile_text, conflict = _build_prompt_context(db, session)
    prompt = build_mediation_rewrite_prompt(
        inviter_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict,
        inviter_input="\n".join(inviter_inputs) or "（未填写）",
        partner_input="\n".join(partner_inputs) or "（未填写）",
    )

    ai_response = _call_llm(prompt, "mediation_rewrite")

    ai_repo.create_message(
        db, session_id, "assistant",
        json.dumps(ai_response, ensure_ascii=False),
        structured_output=ai_response,
    )
    db.commit()
    return ai_response


def generate_summary(db: Session, session_id: int) -> dict:
    """生成调解总结（状态机 summarizing 由调用方负责）。

    只有双方确认过才该被调用（§8.5-5 的判定在 [confirm_rewrite]），
    所以这里的 all_text 可以带双方全部发言——公开已经发生。
    """
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")

    messages = ai_repo.get_messages_by_session(db, session_id)
    all_text = "\n".join(
        f"{m.role}: {m.content}" for m in messages
    )

    user_profile_text, partner_profile_text, conflict = _build_prompt_context(db, session)
    prompt = build_mediation_summary_prompt(
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict,
        all_text=all_text,
    )

    ai_response = _call_llm(prompt, "mediation_summary")

    ai_repo.create_message(
        db, session_id, "assistant",
        json.dumps(ai_response, ensure_ascii=False),
        structured_output=ai_response,
    )
    db.commit()
    return ai_response


def _get_session(db: Session, session_id: int, user_id: int) -> AiChatSession:
    """取会话并校验参与资格。

    整改 §8.5-6：**completed 不再是拒绝访问的理由**——已完成的调解必须能重新
    查看（总结、双方改写、历史），否则「能回看」这条走查永远过不了。
    写操作的准入由各自的状态机校验（accept 查 completed、submit_input 查
    inputting/accepted、confirm 查 confirming）单独把守。
    """
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")
    if session.user_id != user_id and session.partner_user_id != user_id:
        raise ValueError("50002")
    return session
