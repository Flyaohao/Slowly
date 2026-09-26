import json
import logging
from typing import Optional, Dict, List, Set, Tuple
from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.orm import Session
from starlette.websockets import WebSocket, WebSocketDisconnect

from app.models.ai import AiChatSession, AiChatMessage
from app.models.ai_task import (
    TASK_REGENERATE,
    TASK_REWRITE,
    TASK_SUMMARY,
)
from app.repositories import ai_repo, ai_task_repo, couple_repo, profile_repo
from app.services.safety_service import check_input_safety_detail, get_safety_response
from app.repositories import safety_repo
from app.services.ai_service import _call_llm, _format_profile
from app.services.llm_client import LlmError
from app.services.prompt_builder import (
    build_mediation_rewrite_prompt,
    build_mediation_summary_prompt,
)
from app.services.notification_service import (
    notify_mediation_invite,
    schedule_notify,
)

logger = logging.getLogger(__name__)

#: 会话状态全集。
#:
#: `rewrite_failed` / `summary_failed` 是整改 B4.1 新增的**可区分失败态**：
#: 此前生成失败会静默回落到 confirming，客户端看到的是「AI 正在生成」一直转；
#: 现在失败有独立状态 + 失败码，客户端能给出「重试」而不是假装还在跑。
MEDIATION_STATUSES = [
    "inviting", "accepted", "inputting", "rewriting", "rewrite_failed",
    "confirming", "summarizing", "summary_failed", "completed",
]

#: 契约 §2.3-7：首页 active_mediation 的真实状态集（状态机从不产生 in_progress）。
#: 失败态也算「还在进行中」——用户要能回到那一场去重试，而不是它从首页消失。
ACTIVE_MEDIATION_STATUSES = [
    "inviting", "accepted", "inputting", "confirming",
    "rewriting", "rewrite_failed", "summarizing", "summary_failed",
]

#: 契约 §8.5-8 / 整改 B4.1-2：**所有** LLM 生成都在持久化后台任务里跑，
#: 请求只入队并立刻返回 accepted/processing 语义的状态。这个集合是
#: 「客户端应该继续轮询」的状态集（含失败态：失败也要轮询到用户点了重试）。
ASYNC_GENERATION_STATUSES = {
    "rewriting", "rewrite_failed", "summarizing", "summary_failed",
}

#: 需要用户介入（点重试）才能继续的终态失败
TERMINAL_FAILURE_STATUSES = frozenset({"rewrite_failed", "summary_failed"})

#: 「等生成」状态：**人**停在这里，生成结果将把它推到下一步。
#:
#: 与 `ASYNC_GENERATION_STATUSES` 的区别是「谁在等」：后者是任务**自己**
#: 推进出来的中间态（重试重排后要能再推进，所以是宽集合）；这里是**人**的
#: 等待态——只有人做了新动作（补了输入）才会离开它。生成失败时**绝不能**
#: 把人推到这里来：那时没有任何任务会再跑，用户等的是一个永远不来的结果。
WAITING_GENERATION_STATUSES = frozenset({"confirming"})

#: 契约 §8.5-3：双方独立输入在「明确公开（总结生成）」之前不得互相返回。
#: 这些状态下 GET {id} 的 messages 只回当前用户自己的发言。
MESSAGES_PRIVATE_STATUSES = frozenset({
    "inviting", "accepted", "inputting", "rewriting", "rewrite_failed", "confirming",
})

#: 契约 §8.5-6：已完成的调解可重新查看——completed 不再是「拒绝访问」的理由。
READABLE_STATUSES = frozenset(MEDIATION_STATUSES)

#: `_call_llm` 的降级文案。它**不抛异常**（对话链路靠它保命），但调解的
#: 改写/总结是不能降级的：一份「AI 服务暂时不可用」的改写或总结落库之后，
#: 用户会把它当成真实结论去确认。所以这里识别出来并转成任务失败。
_LLM_DEGRADED_MARKERS = ("AI 服务尚未配置", "AI 服务暂时不可用")


def _raise_if_degraded(ai_response: dict, scene_key: str) -> None:
    """降级响应 → 抛 LlmError，交给任务重试/失败收口。"""
    text = " ".join(
        str(ai_response.get(key) or "") for key in ("raw_text", "summary")
    )
    for marker in _LLM_DEGRADED_MARKERS:
        if marker in text:
            raise LlmError("调解生成降级（%s）：%s" % (scene_key, marker))



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


def _claim_transition(
    db: Session,
    session_id: int,
    expected: Tuple[str, ...],
    new_status: str,
) -> bool:
    """条件 UPDATE 的原子状态转换；返回是否**由本次调用完成**了转换。

    为什么必须原子：`submit_input` / `confirm_rewrite` 都是「先读状态、再写状态」，
    两个请求同时进来会双双读到 `inputting`，于是各自创建任务、各自广播，
    同一场调解被推进两次。条件 UPDATE 把「判断」与「推进」压成一条 SQL，
    rowcount 决定谁赢——后到的那个拿到 0，按既有状态幂等返回。

    SQLite 同样支持本写法（UPDATE ... WHERE 状态条件），所以 hermetic 测试
    走的是与生产完全相同的路径。
    """
    result = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status.in_(tuple(expected)),
        )
        .values(mediation_status=new_status)
    )
    db.commit()
    return result.rowcount == 1


def _bump_revision(db: Session, session_id: int) -> int:
    """推进会话内容版本（原子自增）；返回推进后的版本。

    版本是「任务结果是否还新鲜」的唯一判据：任务只写回版本仍等于自己那一版
    的结果，因此**旧任务不可能覆盖新版本结果**。
    """
    db.execute(
        update(AiChatSession)
        .where(AiChatSession.id == session_id)
        .values(mediation_revision=AiChatSession.mediation_revision + 1)
    )
    db.commit()
    return _current_revision(db, session_id)


def _current_revision(db: Session, session_id: int) -> int:
    row = (
        db.query(AiChatSession.mediation_revision)
        .filter(AiChatSession.id == session_id)
        .first()
    )
    return int(row[0] or 0) if row else 0


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

    if both_submitted:
        # 整改 B4.1-3：双方同时提交时，只有**一个**请求能把会话从
        # inputting/accepted 推进到 rewriting，另一个拿到 rowcount=0，
        # 走下面的幂等返回。任务本身还额外有唯一约束兜底（同版本同类型只有一行）。
        if _claim_transition(
            db, session_id, ("inputting", "accepted"), "rewriting"
        ):
            # 版本推进：本轮的改写产出属于这个版本
            _bump_revision(db, session_id)
            session = _reload(db, session_id)
            _enqueue_rewrite(db, session, requested_by_user_id=user_id)
            _broadcast_status(session)
        return {
            "session_id": session_id,
            "mediation_status": "rewriting",
            "partner_submitted": session.partner_user_id in submitted,
            "accepted": True,
        }

    _broadcast_status(session)
    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "partner_submitted": session.partner_user_id in submitted,
        "accepted": True,
    }


def _enqueue_rewrite(
    db: Session,
    session: AiChatSession,
    *,
    requested_by_user_id: Optional[int],
    regenerate: bool = False,
    supplement: Optional[str] = None,
) -> None:
    """把改写（或只重生成某一侧）排进持久化任务队列，然后催一下 worker。"""
    from app.services import ai_task_service

    payload = {}
    if supplement:
        # 载荷里**不存补充说明原文**：它在消息表里已有归属，任务只需要知道
        # 「重生成哪一侧」这类 ID 级信息（隐私红线：敏感正文不冗余存储）。
        payload["has_supplement"] = True
    ai_task_service.ensure_task(
        db,
        session,
        TASK_REGENERATE if regenerate else TASK_REWRITE,
        requested_by_user_id=requested_by_user_id,
        payload=payload,
    )
    ai_task_service.kick()



def _confirm_response(
    db: Session, session_id: int, user_id: int
) -> dict:
    """确认动作的统一回执：状态 + 双方确认标记（都从库里重新读，不用内存旧值）。"""
    session = _reload(db, session_id)
    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "my_confirmed": _my_confirm_at(session, user_id) is not None,
        "partner_confirmed": _partner_confirmed(session, user_id),
        "accepted": True,
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
      （等待态），双方齐了才进 summarizing 并在后台任务里生成总结。
    - 需要修改 → 补充说明落库，**只重生成自己这一侧**的改写，对方那一侧的内容
      与确认状态都不动（此前双方一起重写，等于替对方改了稿）。

    整改 B4.1-3（并发正确性）：本函数所有「离开 confirming」的动作都走
    `_claim_transition` 的条件 UPDATE，所以：

    - 双方同时点确认 → 只有一个请求能把会话推进到 summarizing，
      另一个拿到 rowcount=0 后按既有状态返回（不会建第二个总结任务）；
    - 重复点击 / 超时重发 → 本人已确认时**直接短路**（幂等，不重复落库建任务）；
    - 旧任务写回不会覆盖新版本（写回前比对 `mediation_revision`）。
    """
    session = _get_session(db, session_id, user_id)
    if session.mediation_status != "confirming":
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    # 幂等短路：本人已经确认过，再点一次没有新语义（客户端重试/双击都走这里）
    if confirmed and _my_confirm_at(session, user_id) is not None:
        return _confirm_response(db, session_id, user_id)

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
        if _claim_transition(db, session_id, ("confirming",), "rewriting"):
            _bump_revision(db, session_id)
            fresh = _reload(db, session_id)
            _enqueue_rewrite(
                db, fresh,
                requested_by_user_id=user_id,
                regenerate=True,
                supplement=supplement,
            )
            _broadcast_status(fresh)
        return _confirm_response(db, session_id, user_id)

    # 审查 H2 的**按人版本**：自己那一侧没有改写文本时，绝不记下确认——
    # 否则两级确认齐了就出一个「整场没有改写」的总结。缺失时**补生成一次**。
    #
    # 整改 B4.1-2：补生成同样是一次 LLM 调用，此前是同步跑（用户要等 120s）。
    # 现在与首次改写走同一条后台任务路径：状态翻回 rewriting，客户端继续轮询。
    if not _my_rewrite_text(db, session, user_id):
        if _claim_transition(db, session_id, ("confirming",), "rewriting"):
            session = _reload(db, session_id)
            _bump_revision(db, session_id)
            fresh = _reload(db, session_id)
            _enqueue_rewrite(
                db, fresh, requested_by_user_id=user_id, regenerate=True
            )
            _broadcast_status(fresh)
        return _confirm_response(db, session_id, user_id)

    _set_my_confirm(session, user_id, datetime.now())
    db.commit()

    session = _reload(db, session_id)
    if not _both_confirmed(session):
        # 对方还没确认：停在 confirming，不生成总结——§8.5-5 的硬门。
        _broadcast_status(session)
        return _confirm_response(db, session_id, user_id)

    # 双方确认齐了 → summarizing（后台任务生成总结，§8.5-8）。
    # CAS：双方同时点确认时，只有先到的那个请求能建总结任务。
    if _claim_transition(db, session_id, ("confirming",), "summarizing"):
        session = _reload(db, session_id)
        _bump_revision(db, session_id)
        session = _reload(db, session_id)
        _enqueue_summary(db, session, requested_by_user_id=user_id)
        _broadcast_status(session)
    return _confirm_response(db, session_id, user_id)


def _enqueue_summary(
    db: Session, session: AiChatSession, *, requested_by_user_id: Optional[int]
) -> None:
    """把总结排进持久化任务队列，然后催一下 worker。"""
    from app.services import ai_task_service

    ai_task_service.ensure_task(
        db, session, TASK_SUMMARY, requested_by_user_id=requested_by_user_id
    )
    ai_task_service.kick()


def retry_generation(db: Session, session_id: int, user_id: int) -> dict:
    """整改 B4.1-4：失败后的用户重试（`rewrite_failed` / `summary_failed`）。

    不新建会话、不丢已有输入与改写——只是把失败的那一步重新排进任务队列。
    """
    from app.services import ai_task_service

    session = _get_session(db, session_id, user_id)
    if session.mediation_status not in TERMINAL_FAILURE_STATUSES:
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    if session.mediation_status == "summary_failed":
        resumed = "summarizing"
        task_type = TASK_SUMMARY
    else:
        # 改写失败：回到 rewriting（重跑改写任务，成功即进 confirming）
        resumed = "rewriting"
        task_type = TASK_REWRITE

    if _claim_transition(db, session_id, TERMINAL_FAILURE_STATUSES, resumed):
        _bump_revision(db, session_id)
        session = _reload(db, session_id)
        # 版本已推进：任务必须在**新版本**上重跑。同一版本的旧任务（已 failed）
        # 是一条历史记录，不复用它——`ensure_task` 会按新版本建一行。
        ai_task_service.ensure_task(
            db, session, task_type, requested_by_user_id=user_id
        )
        ai_task_service.kick()
        _broadcast_status(session)

    return {
        "session_id": session_id,
        "mediation_status": (_reload(db, session_id)).mediation_status,
        "accepted": True,
    }


def _partner_confirmed(session: AiChatSession, user_id: int) -> bool:
    """对方是否已确认（用于状态展示；与「我是否确认」对称，不含己方）。"""
    if session.user_id == user_id:
        return session.confirm_partner_at is not None
    return session.confirm_inviter_at is not None


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
    #
    # 边界：公开 = 总结生成**开始**（summarizing）。summary_failed（总结失败、
    # 可重试）同样算已公开——失败的只是总结本身，双方改写在进入 summarizing
    # 那一刻就已经互相可见；如果失败后把它收回去，用户会在两个状态间看到
    # 对方内容"闪现又消失"，比公开更糟。生成**之前**的失败态
    # （rewrite_failed）不公开：改写还没成稿，那一步的双方输入仍应互不可见。
    revealed = session.mediation_status in (
        "summarizing", "completed", "summary_failed",
    )
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
        # ---- 整改 B4.1-4：可靠后台任务的**可观察状态** ----
        # 客户端据此区分「还在生成」/「失败可重试」/「失败已终态」，
        # 而不是只能看到 processing 一直转。
        "revision": int(session.mediation_revision or 0),
        "failure": _failure_payload(session),
        "task": _task_payload(db, session),
        "my_rewrite": my_rewrite,
        "partner_rewrite": partner_rewrite,
        "rewrites": rewrites,
        "messages": [
            _message_payload(m, my_role, revealed) for m in messages
        ],
    }


def _failure_payload(session: AiChatSession) -> Optional[dict]:
    """失败信息（无失败时为 None）。

    `retryable` 的语义是「客户端可以给用户一个重试按钮」——终态失败
    （TASK_EXHAUSTED）与还在自动退避的失败都给 true：前者靠用户手动，
    后者用户点一下也会立刻重排，两条路都不该把用户堵在死页面上。
    """
    if not session.mediation_failure_code:
        return None
    return {
        "code": session.mediation_failure_code,
        "message": session.mediation_last_error,
        "failed_at": (
            session.mediation_failed_at.isoformat()
            if session.mediation_failed_at else None
        ),
        "retryable": True,
    }


def _task_payload(db: Session, session: AiChatSession) -> Optional[dict]:
    """当前任务的可观测摘要：第几次尝试、上限、下次重试时间。

    只暴露计数字段，**不含 payload**（载荷里可能含引用 id，没必要下发）。
    """
    task_id = (
        session.summary_task_id
        if session.mediation_status in ("summarizing", "summary_failed")
        else session.rewrite_task_id
    )
    if not task_id:
        return None
    task = ai_task_repo.get_task_by_id(db, task_id)
    if task is None:
        return None
    return {
        "id": task.id,
        "type": task.task_type,
        "state": task.state,
        "attempt": int(task.attempt or 0),
        "max_attempts": int(task.max_attempts or 0),
        "next_retry_at": (
            task.next_retry_at.isoformat() if task.next_retry_at else None
        ),
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

    **纯生成函数**：只产内容并落一条 assistant 消息，不碰状态机、不 commit
    状态。状态推进由任务执行器（`run_rewrite_task` / `run_regenerate_task`）
    负责，它才掌握租约、版本与失败收口。

    prompt 一律走 `prompt_builder`（代码风格约束：提示词不得硬编码在业务函数里）。
    """
    session = _reload(db, session_id)
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
    _raise_if_degraded(ai_response, "mediation_rewrite")
    return ai_response


def generate_summary(db: Session, session_id: int) -> dict:
    """生成调解总结（纯生成；状态机由任务执行器负责）。

    只有双方确认过才该被调用（§8.5-5 的判定在 [confirm_rewrite]），
    所以这里的 all_text 可以带双方全部发言——公开已经发生。
    """
    session = _reload(db, session_id)
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
    _raise_if_degraded(ai_response, "mediation_summary")
    return ai_response


# --------------------------------------------------------------------------- #
# 任务执行器（由 `ai_task_service` 的 worker 调用）
#
# 这三个函数是**唯一**会推进「生成中 → 生成完成/失败」状态的地方。
# 它们都遵守同一条纪律：**写回前先比对版本**——会话的 `mediation_revision`
# 若已被推进（用户在别处点了重试、或又改了一稿），本任务的产出直接丢弃，
# 绝不覆盖更新的结果。
# --------------------------------------------------------------------------- #

def _task_still_fresh(db: Session, session_id: int, task_revision: int) -> bool:
    return _current_revision(db, session_id) == int(task_revision or 0)


def run_rewrite_task(db: Session, task) -> None:
    """执行一次「双方改写」任务：rewriting → confirming。"""
    session = _reload(db, task.session_id)
    if session is None:
        raise ValueError("50001")

    result = process_rewrite(db, task.session_id)
    _commit_generation_result(db, task, result, "confirming")


def run_regenerate_task(db: Session, task) -> None:
    """「需要修改」：只重生成**触发者那一侧**的改写，对方那侧原样保留。

    与首次改写的唯一区别是合并策略：取上一份改写的对侧文本作为该侧内容，
    这样对方在他自己的页面上看到的仍是原稿，不会被悄悄换掉。
    """
    session = _reload(db, task.session_id)
    if session is None:
        raise ValueError("50001")

    messages = ai_repo.get_messages_by_session(db, task.session_id)
    prev = next(
        (
            m for m in reversed(messages)
            if m.role == "assistant" and m.structured_output
            and (
                m.structured_output.get("rewrite_a")
                or m.structured_output.get("rewrite_b")
            )
        ),
        None,
    )
    keep_a = (prev.structured_output or {}).get("rewrite_a", "") if prev else ""
    keep_b = (prev.structured_output or {}).get("rewrite_b", "") if prev else ""

    fresh = process_rewrite(db, task.session_id) or {}

    # 触发者身份：任务创建时记的 requested_by_user_id；缺失（系统补偿）时按发起方侧处理
    trigger_id = task.requested_by_user_id
    mine_is_a = trigger_id is None or session.user_id == trigger_id
    merged = {
        "rewrite_a": fresh.get("rewrite_a", "") if mine_is_a else keep_a,
        "rewrite_b": (keep_b if mine_is_a else fresh.get("rewrite_b", "")),
        "risk_level": fresh.get("risk_level", "normal"),
    }
    _commit_generation_result(db, task, merged, "confirming")


def run_summary_task(db: Session, task) -> None:
    """执行一次「关系总结」任务：summarizing → completed。"""
    result = generate_summary(db, task.session_id)
    _commit_generation_result(db, task, result, "completed")


def _commit_generation_result(
    db: Session, task, payload: dict, new_status: str
) -> None:
    """把生成结果写回会话：**「推进状态」与「落结果消息」在同一次 commit 里**。

    为什么要原子（整改 B4.1-3 的收尾）：

    - 先落消息再推状态：两步之间进程被杀 → 消息在、状态还停在 rewriting，
      租约到期后任务重跑 → 又落一条消息。用户看到两份改写、两份总结。
    - 先推状态再落消息：中间被杀 → 状态已 confirming 却没有任何改写，
      而重跑时的 CAS 不再命中 `rewriting`（状态已变），产出被当成过期丢弃，
      会话就永久停在「确认页 + 没有稿子」。

    合并成一次提交后，这两种半成品都不存在：要么状态和消息一起生效，
    要么一起回滚，任务留在 running（租约到期重跑）。

    写回前两道闸：

    1. **版本**：`mediation_revision` 已被推进 → 产出属于旧版本，丢弃；
    2. **状态 CAS**：见 [_claim_generation]——只有仍在**等这个结果**的状态
       才能被推进，且 rowcount==1 才落消息。两个执行者（租约过期后的重复
       执行）同时回来时，只有一个能推进成功。

    **「等这个结果」只能由既有的等生成态或失败态本身来判定**，所以本函数
    不预设结果的状态（`new_status` 只用于首次推进），而是看 CAS 到底从哪个
    状态推上来的：

    - 从 `rewriting` / `summarizing`（首次生成或退避重试）→ `new_status`；
    - 从 `rewrite_failed` / `summary_failed`（用户点了重试、状态已被
      `retry_generation` 复位）→ 重新落结果并**回到 `new_status`**。

    为什么必须允许从失败态推进（否则会永久丢稿）：用户点重试时
    `retry_generation` 会把会话从失败态复位成 `rewriting`，**重置的是会话，
    不是任务行**——任务可能已经耗尽了 attempt。它重跑回来时 session 早已
    不在 rewriting（可能被另一个执行者推到了 confirming），若 CAS 只认
    「等生成态」，这次成功的结果就被丢掉，而任务行已被 `mark_succeeded`
    记成成功 → 会话永久停在「重试中」且永远没有新稿。
    """
    if not _task_still_fresh(db, task.session_id, task.revision):
        logger.info(
            "任务产出已过期，丢弃 task=%s session=%s rev=%s",
            task.id, task.session_id, task.revision,
        )
        db.rollback()
        ai_task_repo.mark_superseded(db, task)
        return

    claimed_from = _claim_generation(db, task.session_id, new_status)
    if not claimed_from:
        # 会话已被别的执行者/用户动作推进：本执行的产出无处可写
        logger.info(
            "生成状态已被他人推进，丢弃产出 task=%s session=%s",
            task.id, task.session_id,
        )
        db.rollback()
        ai_task_repo.mark_superseded(db, task)
        return

    ai_repo.create_message(
        db, task.session_id, "assistant",
        json.dumps(payload, ensure_ascii=False),
        structured_output=payload,
    )
    db.commit()
    # 收口任务行。放在业务提交**之后**：万一它没写成（任务行已被回收/被别人
    # 接管），业务结果也已经落地，不会出现「结果在、任务还被重跑」。
    # 失败路径的归属判据同理，见 `ai_task_repo._finalize`。
    ai_task_repo.mark_succeeded(db, task)
    final_status = _apply_generation_result(db, task.session_id, claimed_from, new_status)
    broadcast_status_by_id(db, task.session_id, final_status)


def _apply_generation_result(
    db: Session, session_id: int, claimed_from: str, new_status: str
) -> str:
    """结果落库后的最终状态。返回真正生效的状态（用于广播）。

    失败态是「**没有在跑的生成**」：结果落到这里，会话必须立刻回到用户可操作
    的状态（confirming / completed）。所以从失败态推进时补一次状态写作；
    从等生成态推进时 `_claim_generation` 已经写好了，这里什么都不做。
    """
    if claimed_from not in TERMINAL_FAILURE_STATUSES:
        return new_status
    result = db.execute(
        update(AiChatSession)
        .where(AiChatSession.id == session_id)
        .values(
            mediation_status=new_status,
            mediation_failure_code=None,
            mediation_last_error=None,
            mediation_failed_at=None,
        )
    )
    db.commit()
    if result.rowcount != 1:  # 理论上不会发生：CAS 刚拿到过这一行
        logger.warning("生成结果落库后状态写作未命中 session=%s", session_id)
        return (_reload(db, session_id)).mediation_status
    return new_status


def _claim_generation(db: Session, session_id: int, new_status: str) -> Optional[str]:
    """生成成功时的状态推进（**不提交**，交给调用方与消息一起 commit）。

    返回**被推进时的状态**（判定不了就返回 None），调用方据此区分「首次生成」
    与「失败后的重跑」。允许被推进的状态见 [_commit_generation_result] 的说明：
    等生成态 + 失败态（保守性保护：两个执行者同时回来时只有一个 rowcount=1）。
    """
    claimable = tuple(ASYNC_GENERATION_STATUSES | WAITING_GENERATION_STATUSES)
    row = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status.in_(claimable),
        )
        .values(
            mediation_status=new_status,
            mediation_failure_code=None,
            mediation_last_error=None,
            mediation_failed_at=None,
        )
    )
    if row.rowcount != 1:
        return None
    return db.execute(
        select(AiChatSession.mediation_status).where(AiChatSession.id == session_id)
    ).scalar_one()


def broadcast_status_by_id(db: Session, session_id: int, status: str) -> None:
    """按会话 id 广播状态帧。

    任务执行器跑在 worker 线程里、拿到的是自己的 Session，而 WS 广播需要
    会话成员——所以由调用方把 db 传进来，本函数不自己开会话（避免在请求
    线程之外凭空连库）。
    """
    session = _reload(db, session_id)
    if session is None:
        return
    frame = {
        "type": "mediation_status",
        "session_id": session_id,
        "status": status,
    }
    members = [uid for uid in (session.user_id, session.partner_user_id) if uid]
    schedule_notify(
        manager.broadcast_to_session(members, frame),
        "mediation_status",
    )




def _get_session(db: Session, session_id: int, user_id: int) -> AiChatSession:
    """取会话并校验参与资格（**绕过 identity map 直读当前行**）。

    为什么必须绕过：本模块大量使用「Core UPDATE + commit」做原子状态转换
    （CAS 推进、版本自增），而 `SessionLocal` 是 `expire_on_commit=False`——
    commit 不会让内存对象失效，于是后续 `db.query(...)` 拿回的还是**更新前**
    的那份对象：广播出去的帧、返回给客户端的回执都会是旧状态，
    而数据库里已经是新状态。`populate_existing()` 强制用当前行覆盖内存值，
    让「读回来的就是库里的」。

    §8.5-6：**completed 不再是拒绝访问的理由**——已完成的调解必须能重新查看
    （总结、双方改写、历史），否则「能回看」这条走查永远过不了。写操作的准入
    由各自的状态机校验（accept 查 completed、submit_input 查 inputting/accepted、
    confirm 查 confirming）单独把守。
    """
    session = (
        db.query(AiChatSession)
        .filter(AiChatSession.id == session_id)
        .populate_existing()
        .first()
    )
    if not session:
        raise ValueError("50001")
    if session.user_id != user_id and session.partner_user_id != user_id:
        raise ValueError("50002")
    return session


def _reload(db: Session, session_id: int) -> AiChatSession:
    """成功 CAS 之后重读会话行（拿到新状态再做广播/回执）。"""
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.id == session_id)
        .populate_existing()
        .first()
    )
