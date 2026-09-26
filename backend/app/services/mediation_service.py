import json
import logging
from typing import Optional, Dict, List, Set, Tuple
from datetime import datetime

from sqlalchemy.orm import Session
from starlette.websockets import WebSocket, WebSocketDisconnect

from app.models.ai import AiChatSession, AiChatMessage
from app.repositories import ai_repo, couple_repo, profile_repo
from app.services.safety_service import check_input_safety_detail, get_safety_response
from app.repositories import safety_repo
from app.services.ai_service import _call_llm, _format_profile
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


MEDIATION_REWRITE_PROMPT = """你是一位专业的关系调解师。下面是一对伴侣在矛盾中各自写下的感受。

## 发起方画像（A 方）
{user_profile}

## 参与方画像（B 方）
{partner_profile}

## 冲突模式
{conflict_pattern}

## 发起方（A 方）写下的话
{inviter_input}

## 参与方（B 方）写下的话
{partner_input}

## 指导原则
1. 保持中立，不评判任何一方
2. 改写 A（发起方）：只表达自己的感受与需求，去掉指责、翻旧账、绝对化措辞（如"你总是""你从来不"）
3. 改写 B（参与方）：先承接对方的感受，再表达自己的立场，去掉防御性与反击性措辞
4. 保留双方的核心诉求与真实情绪，不要粉饰矛盾
5. 语言口语化、真诚，像两个人在好好说话，不要书面腔
6. 不使用"可能""也许"之类的推测措辞，这是改写而非解读

请调用工具提交结果，其中 rewrite_a = 发起方（A 方）的改写，rewrite_b = 参与方（B 方）的改写，
两侧都必须是改写后的完整表达。"""


MEDIATION_SUMMARY_PROMPT = """你是一位专业的关系调解师。下面是一对伴侣在调解过程中的全部对话记录。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 调解记录
{all_text}

## 指导原则
1. 站在中立立场，客观归纳，不偏袒任何一方
2. common_points 写双方真正一致的地方，而不是场面话
3. differences 写双方尚未达成一致的差异，措辞中性，不带评判
4. next_actions 必须是双方立刻能执行的具体动作，例如"今晚睡前各说一件今天对方做的让你舒服的事"
5. 不要编造记录中不存在的信息

请调用工具提交结果。"""


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
        # 功能设计 §7：双方输入完成后先出改写，confirm 页才有内容可确认；
        # 生成失败不吞输入——回到 confirming，confirm 时兜底重试。
        session.mediation_status = "rewriting"
        db.commit()
        _broadcast_status(session)
        try:
            process_rewrite(db, session_id)
        except Exception:  # noqa: BLE001
            logger.warning("调解改写生成失败 session=%s", session_id, exc_info=True)
        # 成功/失败都进 confirming：FE 确认页要有内容可确认，失败时 confirm
        # 会兜底重试改写（§2.3-6）
        session = ai_repo.get_session_by_id(db, session_id)
        if session is not None:
            session.mediation_status = "confirming"
            db.commit()

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
    session = _get_session(db, session_id, user_id)
    if session.mediation_status != "confirming":
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    # 补充说明作为该用户的补充发言落库（进入按作者分组的改写输入）
    if supplement and supplement.strip():
        ai_repo.create_message(db, session_id, "user", supplement.strip(), user_id=user_id)
        db.commit()

    if not confirmed:
        # 功能设计 §7：确认不准确 → 补充/重新改写，停在 rewriting → confirming
        return _regenerate_rewrite(db, session_id)

    # 契约 §2.3-6：confirm 接线 process_rewrite（兜底——正常路径双方输入时已生成）
    messages = ai_repo.get_messages_by_session(db, session_id)
    has_rewrite = _has_rewrite(messages)
    if not has_rewrite:
        # 审查 H2 关联：兜底改写失败（_regenerate_rewrite 抛 50000）或
        # 「看似成功却没产出改写」时，停在 confirming 返回明确错误，
        # 不推进 summarizing——否则会出现 completed 但整场没有改写的会话。
        _regenerate_rewrite(db, session_id)
        messages = ai_repo.get_messages_by_session(db, session_id)
        if not _has_rewrite(messages):
            raise ValueError("50000")

    # summary 在 summarizing 阶段接线 generate_summary
    session = ai_repo.get_session_by_id(db, session_id)
    session.mediation_status = "summarizing"
    db.commit()
    _broadcast_status(session)
    try:
        generate_summary(db, session_id)
    except Exception:  # noqa: BLE001
        logger.warning("调解总结生成失败 session=%s", session_id, exc_info=True)
        # 失败回 confirming，FE 可重试 confirm；不吞已有改写
        session = ai_repo.get_session_by_id(db, session_id)
        session.mediation_status = "confirming"
        db.commit()
        raise ValueError("50000")
    session = ai_repo.get_session_by_id(db, session_id)
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": session.mediation_status}


def _has_rewrite(messages) -> bool:
    """会话消息里是否已有可用改写（rewrite_a / rewrite_b 任一非空）。"""
    return any(
        m.role == "assistant"
        and m.structured_output
        and (m.structured_output.get("rewrite_a") or m.structured_output.get("rewrite_b"))
        for m in messages
    )


def _regenerate_rewrite(db: Session, session_id: int) -> dict:
    session = ai_repo.get_session_by_id(db, session_id)
    session.mediation_status = "rewriting"
    db.commit()
    _broadcast_status(session)
    try:
        process_rewrite(db, session_id)
    except Exception:  # noqa: BLE001
        logger.warning("调解改写生成失败 session=%s", session_id, exc_info=True)
        # 审查 H2 关联：失败同样先回到 confirming（状态不悬在 rewriting），
        # 但**不再吞异常**——明确抛 50000 给调用方，否则 confirm 分支会
        # 误以为改写就绪而推进 summarizing，产出 completed 却无改写的会话。
        session = ai_repo.get_session_by_id(db, session_id)
        session.mediation_status = "confirming"
        db.commit()
        _broadcast_status(session)
        raise ValueError("50000")
    # 成功也回到 confirming：confirm(false) 重新改写的落点（FE 再次 confirm）
    session = ai_repo.get_session_by_id(db, session_id)
    session.mediation_status = "confirming"
    db.commit()
    _broadcast_status(session)
    return {"session_id": session_id, "mediation_status": session.mediation_status}


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
    messages = ai_repo.get_messages_by_session(db, session_id)
    my_role = _my_role(session, user_id)

    # 契约 §2.4-2：对方是否已提交（distinct user，NULL 旧数据不计）
    partner_submitted = any(
        m.role == "user" and m.user_id == session.partner_user_id
        for m in messages
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
        inviter_original = _last_input(messages, session.user_id)
        partner_original = _last_input(messages, session.partner_user_id)
        rewrites = [
            {"author_user_id": session.user_id, "content": inviter_text},
            {"author_user_id": session.partner_user_id, "content": partner_text},
        ]
        if my_role == "inviter":
            my_rewrite = {"original": inviter_original, "rewritten": inviter_text}
            partner_rewrite = {"original": partner_original, "rewritten": partner_text}
        else:
            my_rewrite = {"original": partner_original, "rewritten": partner_text}
            partner_rewrite = {"original": inviter_original, "rewritten": inviter_text}

    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "session_type": session.session_type,
        "user_id": session.user_id,
        "partner_user_id": session.partner_user_id,
        # ---- 以下均为契约 §2.3-2 / §2.4 只增不减的新字段 ----
        "my_role": my_role,
        "partner_submitted": partner_submitted,
        "updated_at": session.updated_at.isoformat() if session.updated_at else None,
        "my_rewrite": my_rewrite,
        "partner_rewrite": partner_rewrite,
        "rewrites": rewrites,
        "messages": [
            {
                "id": m.id,
                "role": m.role,
                "content": m.content,
                "structured_output": m.structured_output,
                "risk_level": m.risk_level,
                "created_at": m.created_at.isoformat() if m.created_at else None,
            }
            for m in messages
        ],
    }


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
    prompt = MEDIATION_REWRITE_PROMPT.format(
        user_profile=user_profile_text,
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
    """生成调解总结（状态机 summarizing 由调用方负责）。"""
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")

    messages = ai_repo.get_messages_by_session(db, session_id)
    all_text = "\n".join(
        f"{m.role}: {m.content}" for m in messages
    )

    user_profile_text, partner_profile_text, conflict = _build_prompt_context(db, session)
    prompt = MEDIATION_SUMMARY_PROMPT.format(
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
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")
    if session.user_id != user_id and session.partner_user_id != user_id:
        raise ValueError("50002")
    if session.mediation_status == "completed":
        raise ValueError("50003")
    return session
