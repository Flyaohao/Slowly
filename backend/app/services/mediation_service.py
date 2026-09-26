import json
import logging
from typing import Optional, Dict, List, Set, Tuple
from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
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

#: 注意：`confirming` **不是**「等生成」态。它是**人**停在这里的等待态——
#: 只有人做了新动作（补输入 / 点确认）才会离开它，没有任何在跑的任务会把它
#: 推到下一步。所以它不在 `ASYNC_GENERATION_STATUSES` 里，也**不得**被
#: `_claim_generation` 当作可推进状态（否则重复执行会往确认页再写一份稿）。
#: 生成失败时也**绝不能**把人推到这里来：那时没有任何任务会再跑。

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


#: 合法风险等级（与 `app/schemas/ai_output.RiskLevel`、`safety_service.RISK_LEVEL_ORDER`
#: 逐字一致）。模型的结构化输出**不是可信输入**：大小写、空格、自造值都会出现。
_KNOWN_RISK_LEVELS = (
    "normal", "heated_conflict", "manipulation_risk", "abuse_risk", "self_harm_risk",
)

#: 高风险等级：这些等级下**不得**产出双人调解结果（P0-6「高风险禁止双人调解」）。
#:
#: 依据：调解的产物是「替双方把话说软」。若一方的表达属于控制/威胁（manipulation）、
#: 暴力/胁迫（abuse）或自伤风险（self_harm），把它改写成一份体面的稿子等于替施害
#: 一方粉饰，甚至会成为当事人继续留在有害关系里的理由。正确处置是**停止调解、
#: 给出安全资源**。`heated_conflict` 不在其中——双方情绪激动正是调解要处理的场景。
BLOCKING_RISK_LEVELS = frozenset({
    "manipulation_risk", "abuse_risk", "self_harm_risk",
})


def normalize_risk_level(raw) -> str:
    """把模型给的 `risk_level` 收敛到白名单；未知值按 `normal` 处理。

    归一化本身是安全链路的一环：门控按等级判定，若模型返回 `"High"` 这类
    不在白名单的值，未归一化会让判定静默落空（等于安全链路失效）。
    """
    text = str(raw if raw is not None else "").strip().lower()
    return text if text in _KNOWN_RISK_LEVELS else "normal"


def _normalize_generation_payload(payload: dict, *, summary: bool) -> dict:
    """结构化输出归一化（写库前的唯一收敛点）。

    - `risk_level` 过 [normalize_risk_level]；
    - 改写的两侧文本一律成 str（模型可能给 null / 数字）；
    - 总结的三个列表一律成 `List[str]`（模型可能给 str 或含 null 的列表，
      直接把 None 序列化给客户端会让 Kotlin 侧解析崩在 `List<String>` 上）。
    """
    data = dict(payload or {})
    data["risk_level"] = normalize_risk_level(data.get("risk_level"))
    if summary:
        for key in ("common_points", "differences", "next_actions"):
            value = data.get(key)
            if isinstance(value, str):
                value = [value.strip()] if value.strip() else []
            elif isinstance(value, (list, tuple)):
                value = [str(v).strip() for v in value if str(v or "").strip()]
            else:
                value = []
            data[key] = value
    else:
        for key in ("rewrite_a", "rewrite_b"):
            data[key] = str(data.get(key) or "").strip()
    return data



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


def _active_slot(relation_id: int, user_id: int) -> str:
    """活跃调解并发兜底槽位：同一 (relation, inviter) 只会有一个活跃调解。"""
    return "%s:%s" % (relation_id, user_id)


def _find_active_mediation(
    db: Session, relation_id: int, user_id: int
) -> Optional[AiChatSession]:
    """同一情侣关系中**同一发起者**现有的活跃调解（无则 None）。"""
    return (
        db.query(AiChatSession)
        .filter(
            AiChatSession.relation_id == relation_id,
            AiChatSession.user_id == user_id,
            AiChatSession.session_type == "mediation",
            AiChatSession.mediation_status.in_(ACTIVE_MEDIATION_STATUSES),
        )
        .order_by(AiChatSession.created_at.desc())
        .populate_existing()
        .first()
    )


def _start_payload(session: AiChatSession, user_id: int) -> dict:
    return {
        "session_id": session.id,
        "partner_user_id": session.partner_user_id,
        "mediation_status": session.mediation_status,
        # 契约 §2.3-1：start 只有发起方能调，固定 inviter
        "my_role": "inviter",
    }


def start_mediation(db: Session, user_id: int, relation_id: int) -> dict:
    """发起双人调解（**幂等**）。

    同一情侣关系中同一发起者不能同时有多场活跃调解：重复 start（网络重试、
    响应丢失、双击、双设备）返回**现有活跃会话**，方便客户端恢复。

    数据库层并发兜底：不是「先 SELECT 再 INSERT」，而是把活跃槽位
    `mediation_active_slot` 写进唯一约束——两个并发 start 只有一个能插入成功，
    另一个命中 IntegrityError 后回读现有会话。
    """
    relation = couple_repo.get_relation_by_id(db, relation_id)
    if not relation:
        raise ValueError("50001")

    existing = _find_active_mediation(db, relation_id, user_id)
    if existing is not None:
        return _start_payload(existing, user_id)

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
        mediation_active_slot=_active_slot(relation_id, user_id),
    )
    db.add(session)
    try:
        with db.begin_nested():
            db.flush()
    except IntegrityError:
        # 并发兜底：另一请求已插入同槽位 → 只回滚本次 INSERT，回读并返回现有会话
        existing = _find_active_mediation(db, relation_id, user_id)
        if existing is None:
            raise
        return _start_payload(existing, user_id)
    db.commit()

    # 契约 §2.3-5：start 成功后必须通知伴侣（仅在真正新建时通知，幂等返回不重发）
    _notify_invite(db, user_id, partner_id, session.id)

    return _start_payload(session, user_id)


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
    """接受邀请：**仅 inviting → inputting**（原子状态转换）。

    - 已 inputting 的重复 accept 幂等返回当前状态（双击/重试不倒退）；
    - rewriting / confirming / summarizing / completed / 失败态均不得倒退；
    - 只有伴侣能接受（发起方自己接受 = 无权限）。
    """
    session = _get_session(db, session_id, user_id)
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    if session.mediation_status == "inputting":
        return {"session_id": session_id, "mediation_status": "inputting"}
    if session.mediation_status != "inviting":
        raise ValueError("50003")

    # 条件 UPDATE：不能先读后写（并发下两个 accept 只有一个 rowcount=1）
    result = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status == "inviting",
        )
        .values(mediation_status="inputting")
    )
    db.commit()
    session = _reload(db, session_id)
    if result.rowcount != 1:
        # 并发落败：已被别处推进为 inputting → 幂等返回；否则状态不允许
        if session.mediation_status == "inputting":
            return {"session_id": session_id, "mediation_status": "inputting"}
        raise ValueError("50003")
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": "inputting"}


def reject_mediation(db: Session, session_id: int, user_id: int) -> dict:
    """拒绝邀请：**仅 inviting** 可拒绝（原子），终态 completed（可回看）。

    产品里「中途退出」是独立的 cancel/end 操作（`next_step("end")`），
    不复用 reject——reject 只属于「还没接受」的阶段。
    """
    session = _get_session(db, session_id, user_id)
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    if session.mediation_status != "inviting":
        raise ValueError("50003")

    result = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status == "inviting",
        )
        .values(mediation_status="completed", mediation_active_slot=None)
    )
    db.commit()
    session = _reload(db, session_id)
    if result.rowcount != 1:
        if session.mediation_status == "completed":
            return {"session_id": session_id, "mediation_status": "completed"}
        raise ValueError("50003")
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": "completed"}


def _claim_transition(
    db: Session,
    session_id: int,
    expected: Tuple[str, ...],
    new_status: str,
) -> bool:
    """条件 UPDATE 的原子状态转换；返回是否**由本次调用完成**了转换。

    **不提交**（P0-1）：状态 CAS 只是「入队原子事务」的第一步，与 revision
    自增、任务插入、task_id 回写由调用方同一次 commit 收口。这里只把
    「判断」与「推进」压成一条 SQL，rowcount 决定谁赢——后到的那个拿到 0，
    按既有状态幂等返回。

    为什么必须原子：`submit_input` / `confirm_rewrite` 都是「先读状态、再写状态」，
    两个请求同时进来会双双读到 `inputting`，于是各自创建任务、各自广播，
    同一场调解被推进两次。条件 UPDATE 把「判断」与「推进」压成一条 SQL。
    """
    result = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status.in_(tuple(expected)),
        )
        .values(mediation_status=new_status)
    )
    return result.rowcount == 1


def _bump_revision(db: Session, session_id: int) -> int:
    """推进会话内容版本（原子自增）；返回推进后的版本。**不提交**（P0-1）。

    版本是「任务结果是否还新鲜」的唯一判据：任务只写回版本仍等于自己那一版
    的结果，因此**旧任务不可能覆盖新版本结果**。
    """
    db.execute(
        update(AiChatSession)
        .where(AiChatSession.id == session_id)
        .values(mediation_revision=AiChatSession.mediation_revision + 1)
    )
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

    # 契约 §2.4-1：user 消息必须带作者，按人判定/按作者分组都靠它。
    # 幂等：同一用户重复提交**完全相同**的内容不追加第二条（双击/网络重试/
    # 超时重发）；不同内容仍按条落库（用户可以追加倾诉）。
    messages = ai_repo.get_messages_by_session(db, session_id)
    already_same = any(
        m.role == "user" and m.user_id == user_id and m.content == content
        for m in messages
    )
    if not already_same:
        ai_repo.create_message(db, session_id, "user", content, user_id=user_id)
        db.commit()
        messages = ai_repo.get_messages_by_session(db, session_id)

    # 契约 §2.4-2：「双方已提交」按 distinct user 判定——同一个人提交两次不算；
    # user_id 为 NULL 的旧数据不参与判定（无从归属）。
    submitted = {
        m.user_id for m in messages
        if m.role == "user" and m.user_id is not None
    }
    both_submitted = (
        session.user_id in submitted and session.partner_user_id in submitted
    )

    if both_submitted:
        # 整改 B4.1-3 + B4.2-P0：双方同时提交时，只有**一个**请求能把会话从
        # inputting/accepted 推进到 rewriting，另一个拿到 rowcount=0，
        # 走下面的幂等返回。任务本身还额外有唯一约束兜底（同版本同类型只有一行）。
        # 状态 CAS + revision + 任务插入 + task_id 回写在 `_enqueue_generation`
        # 里**同一次 commit** 收口。
        _enqueue_generation(
            db, session_id,
            from_statuses=("inputting", "accepted"),
            to_status="rewriting",
            task_type=TASK_REWRITE,
            requested_by_user_id=user_id,
        )
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


def _enqueue_generation(
    db: Session,
    session_id: int,
    *,
    from_statuses: Tuple[str, ...],
    to_status: str,
    task_type: str,
    requested_by_user_id: Optional[int],
    supplement: Optional[str] = None,
) -> bool:
    """原子入队（P0-1）：状态 CAS + revision 自增 + 任务插入 + task_id 回写，
    一次 commit 收口；广播与 kick 只在提交成功后执行。

    rewrite / regenerate / summary / retry 四条路径**统一走这里**。任何一步
    抛异常（包括任务插入撞唯一键、task_id 回写失败）都会回滚整个事务并
    重抛，绝不留下「状态已 rewriting 但任务不存在」的半成品。

    返回是否由本次调用真正推进（拿到 CAS 的那一方）；落败方返回 False，
    调用方按既有状态幂等返回。
    """
    from app.services import ai_task_service

    try:
        if not _claim_transition(db, session_id, from_statuses, to_status):
            return False
        _bump_revision(db, session_id)
        fresh = _reload(db, session_id)
        payload = {}
        if supplement:
            # 载荷里**不存补充说明原文**：它在消息表里已有归属，任务只需要知道
            # 「重生成哪一侧」这类 ID 级信息（隐私红线：敏感正文不冗余存储）。
            payload["has_supplement"] = True
        ai_task_service.ensure_task(
            db, fresh, task_type,
            requested_by_user_id=requested_by_user_id,
            payload=payload,
        )
        db.commit()
    except Exception:
        db.rollback()
        raise
    ai_task_service.kick()
    _broadcast_status(fresh)
    return True



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
        _enqueue_generation(
            db, session_id,
            from_statuses=("confirming",),
            to_status="rewriting",
            task_type=TASK_REGENERATE,
            requested_by_user_id=user_id,
            supplement=supplement,
        )
        return _confirm_response(db, session_id, user_id)

    # 审查 H2 的**按人版本**：自己那一侧没有改写文本时，绝不记下确认——
    # 否则两级确认齐了就出一个「整场没有改写」的总结。缺失时**补生成一次**。
    #
    # 整改 B4.1-2：补生成同样是一次 LLM 调用，此前是同步跑（用户要等 120s）。
    # 现在与首次改写走同一条后台任务路径：状态翻回 rewriting，客户端继续轮询。
    if not _my_rewrite_text(db, session, user_id):
        _enqueue_generation(
            db, session_id,
            from_statuses=("confirming",),
            to_status="rewriting",
            task_type=TASK_REGENERATE,
            requested_by_user_id=user_id,
        )
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
    _enqueue_generation(
        db, session_id,
        from_statuses=("confirming",),
        to_status="summarizing",
        task_type=TASK_SUMMARY,
        requested_by_user_id=user_id,
    )
    return _confirm_response(db, session_id, user_id)


def retry_generation(db: Session, session_id: int, user_id: int) -> dict:
    """整改 B4.1-4：失败后的用户重试（`rewrite_failed` / `summary_failed`）。

    不新建会话、不丢已有输入与改写——只是把失败的那一步重新排进任务队列。
    """
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

    _enqueue_generation(
        db, session_id,
        from_statuses=TERMINAL_FAILURE_STATUSES,
        to_status=resumed,
        task_type=task_type,
        requested_by_user_id=user_id,
    )

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
        # 终止（中途退出）：清空活跃槽位 + 推进版本，一次条件 UPDATE 收口。
        # 推进版本让**在途任务**的写回与失败收口全部失效（它们按 revision
        # 比对），杜绝「点了退出，正在跑的改写失败后又把会话翻回 rewrite_failed」。
        db.execute(
            update(AiChatSession)
            .where(AiChatSession.id == session_id)
            .values(
                mediation_status="completed",
                mediation_active_slot=None,
                mediation_revision=AiChatSession.mediation_revision + 1,
            )
        )
        db.commit()
        session = _reload(db, session_id)
    elif action == "pause":
        pass
    elif action == "continue":
        # 「继续沟通」：把这一场重新打开（结果页的下一步动作）。
        #
        # 必须**重新占用活跃槽位**：completed 时槽位已被释放，不重新占用就断了
        # 「同一发起者最多一场活跃调解」这条不变量——用户可以一边接着谈这一场，
        # 一边再发起一场新的，两场并行生成、两次总结。槽位被别人占着（并发下
        # 另一场刚好开起来）时唯一约束会拒掉，此时按状态冲突返回 50003。
        try:
            result = db.execute(
                update(AiChatSession)
                .where(AiChatSession.id == session_id)
                .values(
                    mediation_status="inputting",
                    mediation_active_slot=_active_slot(
                        session.relation_id, session.user_id
                    ),
                )
            )
            db.commit()
        except IntegrityError:
            db.rollback()
            logger.warning(
                "继续沟通被拒：已存在另一场活跃调解 session=%s", session_id
            )
            raise ValueError("50003")
        if result.rowcount != 1:
            raise ValueError("50003")
        session = _reload(db, session_id)
    else:
        raise ValueError("50005")
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

    写回前三道闸：

    1. **归一化**：`risk_level` 与各字段类型先收敛（见 [_normalize_generation_payload]），
       安全判定只能建立在归一化后的值上；
    2. **版本**：`mediation_revision` 已被推进 → 产出属于旧版本，丢弃；
    3. **安全**：`risk_level` 命中 [BLOCKING_RISK_LEVELS] → 走 [_commit_safety_block]，
       **不落调解结果**；
    4. **状态 CAS**：见 [_claim_generation]——只有仍在**等这个结果**的状态
       才能被推进，且 rowcount==1 才落消息。两个执行者（租约过期后的重复
       执行）同时回来时，只有一个能推进成功。

    **「等这个结果」只能由既有的等生成态或失败态本身来判定**，所以本函数
    不预设结果的状态（`new_status` 只用于首次推进），而是看 CAS 到底从哪个
    状态推上来的：

    - 从 `rewriting` / `summarizing`（首次生成或退避重试）→ `new_status`；
    - 从 `rewrite_failed` / `summary_failed`（用户点了重试、状态已被
      `retry_generation` 复位）→ 重新落结果并**回到 `new_status`**。
    """
    # 归一化必须在**任何判定之前**：后面所有安全门控都按 `risk_level` 判定，
    # 模型给的大小写/自造值必须先收敛，否则门控会静默落空。
    payload = _normalize_generation_payload(payload, summary=(new_status == "completed"))

    if not _task_still_fresh(db, task.session_id, task.revision):
        logger.info(
            "任务产出已过期，丢弃 task=%s session=%s rev=%s",
            task.id, task.session_id, task.revision,
        )
        db.rollback()
        ai_task_repo.mark_superseded(db, task)
        return

    # P0-6：高风险语境下**不产出**调解结果（详见 [BLOCKING_RISK_LEVELS]）。
    if payload["risk_level"] in BLOCKING_RISK_LEVELS:
        _commit_safety_block(db, task, payload["risk_level"])
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
        # 风险等级必须落库：客户端据此渲染安全提示卡；不落库等于把模型的
        # 风险判定丢掉（此前 mediation 的 assistant 消息恒为 NULL）。
        risk_level=payload["risk_level"],
    )
    db.commit()
    # 收口任务行。放在业务提交**之后**：万一它没写成（任务行已被回收/被别人
    # 接管），业务结果也已经落地，不会出现「结果在、任务还被重跑」。
    # 失败路径的归属判据同理，见 `ai_task_repo._finalize`。
    ai_task_repo.mark_succeeded(db, task)
    broadcast_status_by_id(db, task.session_id, new_status)


def _commit_safety_block(db: Session, task, risk_level: str) -> None:
    """高风险：**不产出调解结果**，把会话收口到安全提示（P0-6）。

    为什么不能「照常落稿 + 加个风险角标」：调解产物是「替双方把话说软」。
    在控制/暴力/自伤语境下，一份体面的改写会被当成「继续这样相处也没问题」的
    许可。这里选择**终止这次调解**：

    - 只落一条 assistant 消息，正文即安全资源，结构化输出只带风险等级；
    - 会话置 ``completed``（终态、可回看、释放活跃槽位，日后仍可重新发起）；
    - **不写** ``mediation_failure_code``——它不是失败，是主动阻断，客户端
      不该为此显示「重试」；
    - 记一条安全审计事件（与输入侧同一个出口）。
    """
    if not _task_still_fresh(db, task.session_id, task.revision):
        db.rollback()
        ai_task_repo.mark_superseded(db, task)
        return
    # 用 CAS 收口到 completed：与正常结果路径共用同一套「只允许一个执行者推进」
    # 的保护，顺手释放活跃槽位、清空失败标记。
    if not _claim_generation(db, task.session_id, "completed"):
        db.rollback()
        ai_task_repo.mark_superseded(db, task)
        return

    message = get_safety_response(risk_level) or "你们的对话触发了安全提示，请先照顾好自己。"
    ai_repo.create_message(
        db, task.session_id, "assistant", message,
        structured_output={"risk_level": risk_level, "safety_response": message},
        risk_level=risk_level,
    )
    db.commit()
    ai_task_repo.mark_succeeded(db, task)
    try:
        safety_repo.log_event(
            task.requested_by_user_id, "mediation", "output", risk_level, []
        )
    except Exception:  # noqa: BLE001 —— 审计失败绝不影响主链路
        logger.warning("调解安全事件落库失败 session=%s", task.session_id, exc_info=True)
    logger.warning(
        "调解因高风险被阻断 session=%s task=%s risk=%s",
        task.session_id, task.id, risk_level,
    )
    broadcast_status_by_id(db, task.session_id, "completed")


def _claim_generation(db: Session, session_id: int, new_status: str) -> Optional[str]:
    """生成结果的状态推进（**不提交**，交给调用方与消息一起 commit）。

    返回**被推进前的状态**（观察不到可推进状态、或已被他人抢先就返回 None）。

    为什么要把「先读到的那个状态」再写进 WHERE 条件：这是「同一版本的产出只写回
    一次」的落点。租约过期后同一个任务可能被第二个 worker 重复执行，两个执行者
    都会走到这里；把观察到的状态钉进条件，第二个执行者的 UPDATE 必然
    `rowcount == 0`（状态已被第一个改成新值），于是它的产出被丢弃——
    **过期执行者不得写业务结果**（P0-2）。

    允许被推进的状态只有「正在等这个结果」的那几个（`ASYNC_GENERATION_STATUSES`）：
    等生成态（rewriting / summarizing）与失败态（rewrite_failed / summary_failed，
    用户点过重试后任务重跑）。**刻意不含 `confirming`**：若把确认页也算进去，
    第二个执行者就能在「会话已在确认页、已有稿」时再写一份，正是要杜绝的重复产出。
    """
    claimable = tuple(ASYNC_GENERATION_STATUSES)
    observed = db.execute(
        select(AiChatSession.mediation_status).where(AiChatSession.id == session_id)
    ).scalar_one_or_none()
    if observed not in claimable:
        return None
    values = {
        "mediation_status": new_status,
        "mediation_failure_code": None,
        "mediation_last_error": None,
        "mediation_failed_at": None,
    }
    if new_status == "completed":
        # 终态：释放活跃槽位，允许同一发起者开始下一场调解
        values["mediation_active_slot"] = None
    row = db.execute(
        update(AiChatSession)
        .where(
            AiChatSession.id == session_id,
            AiChatSession.mediation_status == observed,
        )
        .values(**values)
    )
    if row.rowcount != 1:
        return None
    return observed


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
