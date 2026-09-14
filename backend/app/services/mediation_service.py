import json
import logging
from typing import Optional, Dict, List, Set
from datetime import datetime

from sqlalchemy.orm import Session
from starlette.websockets import WebSocket, WebSocketDisconnect

from app.models.ai import AiChatSession, AiChatMessage
from app.repositories import ai_repo, couple_repo
from app.services.safety_service import check_input_safety, get_safety_response
from app.services.ai_service import _call_llm

logger = logging.getLogger(__name__)

MEDIATION_STATUSES = [
    "inviting", "accepted", "inputting", "rewriting",
    "confirming", "summarizing", "completed",
]


MEDIATION_REWRITE_PROMPT = """你是一位专业的关系调解师。下面是一对伴侣在矛盾中各自写下的感受。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 双方写下的话
{user_input}

## 指导原则
1. 保持中立，不评判任何一方
2. 改写 A 方：只表达自己的感受与需求，去掉指责、翻旧账、绝对化措辞（如"你总是""你从来不"）
3. 改写 B 方：先承接对方的感受，再表达自己的立场，去掉防御性与反击性措辞
4. 保留双方的核心诉求与真实情绪，不要粉饰矛盾
5. 语言口语化、真诚，像两个人在好好说话，不要书面腔
6. 不使用"可能""也许"之类的推测措辞，这是改写而非解读

请调用工具提交结果，其中 rewrite_a / rewrite_b 分别是改写后的完整表达。"""


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

    async def connect(self, user_id: int, ws: WebSocket):
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
    return {
        "session_id": session.id,
        "partner_user_id": partner_id,
        "mediation_status": "inviting",
    }


def accept_mediation(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.mediation_status == "completed":
        raise ValueError("50003")
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    session.mediation_status = "inputting"
    db.commit()
    return {"session_id": session_id, "mediation_status": "inputting"}


def reject_mediation(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.partner_user_id != user_id:
        raise ValueError("50002")
    session.mediation_status = "completed"
    db.commit()
    return {"session_id": session_id, "mediation_status": "completed"}


def submit_input(db: Session, session_id: int, user_id: int, content: str) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.mediation_status not in ("inputting", "accepted"):
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    safety_risk = check_input_safety(content)
    if safety_risk != "normal":
        safety_resp = get_safety_response(safety_risk)
        return {"blocked": True, "risk_level": safety_risk, "safety_response": safety_resp}

    ai_repo.create_message(db, session_id, "user", content)
    db.commit()

    # Check if both users have submitted
    messages = ai_repo.get_messages_by_session(db, session_id)
    user_ids_submitted = set()
    for m in messages:
        if m.role == "user":
            # We need to track which user submitted; use session context
            pass
    # Simple approach: if we have at least 2 user messages, move to confirming
    user_msg_count = sum(1 for m in messages if m.role == "user")
    if user_msg_count >= 2:
        session.mediation_status = "confirming"
        db.commit()

    return {"session_id": session_id, "mediation_status": session.mediation_status}


def confirm_rewrite(db: Session, session_id: int, user_id: int, confirmed: bool) -> dict:
    session = _get_session(db, session_id, user_id)
    if session.mediation_status != "confirming":
        raise ValueError("50003")
    if user_id not in (session.user_id, session.partner_user_id):
        raise ValueError("50002")

    if not confirmed:
        session.mediation_status = "inputting"
        db.commit()
        return {"session_id": session_id, "mediation_status": "inputting"}

    return {"session_id": session_id, "mediation_status": "confirming"}


def get_status(db: Session, session_id: int, user_id: int) -> dict:
    session = _get_session(db, session_id, user_id)
    messages = ai_repo.get_messages_by_session(db, session_id)
    return {
        "session_id": session_id,
        "mediation_status": session.mediation_status,
        "session_type": session.session_type,
        "user_id": session.user_id,
        "partner_user_id": session.partner_user_id,
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
    return {"session_id": session_id, "mediation_status": session.mediation_status}


def process_rewrite(db: Session, session_id: int, user_profile_text: str, partner_profile_text: str, conflict_pattern: str) -> dict:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")
    session.mediation_status = "rewriting"
    db.flush()

    messages = ai_repo.get_messages_by_session(db, session_id)
    user_inputs = [m.content for m in messages if m.role == "user"]

    prompt = MEDIATION_REWRITE_PROMPT.format(
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict_pattern,
        user_input="\n".join(user_inputs),
    )

    ai_response = _call_llm(prompt, "mediation_rewrite")

    ai_repo.create_message(db, session_id, "assistant", json.dumps(ai_response, ensure_ascii=False), structured_output=ai_response)

    session.mediation_status = "confirming"
    db.commit()
    return ai_response


def generate_summary(db: Session, session_id: int, user_profile_text: str, partner_profile_text: str, conflict_pattern: str) -> dict:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session:
        raise ValueError("50001")

    messages = ai_repo.get_messages_by_session(db, session_id)
    all_text = "\n".join(f"{m.role}: {m.content}" for m in messages)

    prompt = MEDIATION_SUMMARY_PROMPT.format(
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict_pattern,
        all_text=all_text,
    )

    ai_response = _call_llm(prompt, "mediation_summary")

    ai_repo.create_message(db, session_id, "assistant", json.dumps(ai_response, ensure_ascii=False), structured_output=ai_response)
    session.mediation_status = "summarizing"
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
