from datetime import datetime

from sqlalchemy import func
from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.ai import (
    AiScene,
    AiPromptTemplate,
    AiPromptVersion,
    AiChatSession,
    AiChatMessage,
    AiOutputFeedback,
)


def get_scene_by_key(db: Session, scene_key: str) -> Optional[AiScene]:
    return db.query(AiScene).filter(AiScene.scene_key == scene_key).first()


def get_all_scenes(db: Session) -> List[AiScene]:
    return db.query(AiScene).all()


def get_active_prompt_template(
    db: Session, scene_key: str, user_id: Optional[int] = None
) -> Optional[AiPromptTemplate]:
    """取该场景当前生效的 prompt 模板。

    - **单版本场景**：返回 `status=active` 中 version 最高的一条。
    - **多版本场景（灰度 / A/B 实验）**：在全部 active 版本里按 `user_id` 稳定分流。
      必须是稳定分流而非随机——同一用户若在不同请求间来回命中不同版本，
      同一段对话的 prompt 会横跳，体验被污染、实验数据也不可比。

    `user_id` 传 None 或只有一个候选版本时退化为"取最高版本"，
    与该方法此前的语义完全一致，因此对旧调用方是向后兼容的。
    """
    candidates = (
        db.query(AiPromptTemplate)
        .filter(
            AiPromptTemplate.scene_key == scene_key,
            AiPromptTemplate.status == "active",
        )
        .order_by(AiPromptTemplate.version.asc())
        .all()
    )
    if not candidates:
        return None
    if user_id is None or len(candidates) == 1:
        return candidates[-1]
    return candidates[user_id % len(candidates)]


def save_prompt_version(
    db: Session, user_id: int, relation_id: int, scene_key: str, prompt_content: str
) -> AiPromptVersion:
    pv = AiPromptVersion(
        user_id=user_id,
        relation_id=relation_id,
        scene_key=scene_key,
        prompt_content=prompt_content,
    )
    db.add(pv)
    db.flush()
    return pv


def create_session(
    db: Session,
    user_id: int,
    relation_id: int,
    scene_key: str,
    title: Optional[str],
    privacy_level: str,
    status: str = "active",
    last_message_at: Optional[datetime] = None,
    segment_reason: Optional[str] = None,
) -> AiChatSession:
    """新建会话。P0-10A：默认 status=active、last_message_at=now、
    message_count/token_total 走模型默认 0；segment_reason 记录分段原因
    （首次创建为 None）。
    """
    # 与 MySQL func.now()（created_at）同一时钟：用本地 now 而非 utcnow，
    # 否则 utcnow 比库内 local 时间慢 8h，get_active 永远选中存量行、超时判定失效
    now = last_message_at or datetime.now()
    session = AiChatSession(
        user_id=user_id,
        relation_id=relation_id,
        scene_key=scene_key,
        title=title,
        privacy_level=privacy_level,
        status=status,
        last_message_at=now,
        message_count=0,
        token_total=0,
        segment_reason=segment_reason,
    )
    db.add(session)
    db.flush()
    return session


def get_session_by_id(db: Session, session_id: int) -> Optional[AiChatSession]:
    return db.query(AiChatSession).filter(AiChatSession.id == session_id).first()


def get_active_session(
    db: Session, user_id: int, relation_id: int, scene_key: str
) -> Optional[AiChatSession]:
    """scope 下 status=active 且 last_message_at 最新的一条（GET /sessions/active 共用）。"""
    return (
        db.query(AiChatSession)
        .filter(
            AiChatSession.user_id == user_id,
            AiChatSession.relation_id == relation_id,
            AiChatSession.scene_key == scene_key,
            AiChatSession.status == "active",
        )
        .order_by(func.coalesce(AiChatSession.last_message_at, AiChatSession.created_at).desc())
        .first()
    )


def archive_session(db: Session, session_id: int, reason: str) -> Optional[AiChatSession]:
    """归档：status→archived，并把原因写进 segment_reason（本会话为何结束）。"""
    session = get_session_by_id(db, session_id)
    if session is None:
        return None
    session.status = "archived"
    session.segment_reason = reason
    db.flush()
    return session


def get_sessions_by_user(db: Session, user_id: int) -> List[AiChatSession]:
    # P0-10A：排序改为 last_message_at（追加消息会刷新它）；NULL 回退 created_at
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.user_id == user_id)
        .order_by(func.coalesce(AiChatSession.last_message_at, AiChatSession.created_at).desc())
        .all()
    )


def get_mediation_sessions(
    db: Session, user_id: int, role: str = "mine"
) -> List[AiChatSession]:
    """契约 §2.3-3 伴侣侧调解列表。

    - ``invited``：partner_user_id==me 且 status=='inviting'（待处理邀请）
    - ``mine``（默认）：我发起的且未结束
    - ``all``：我参与的（两个方向）且未结束
    """
    q = db.query(AiChatSession).filter(
        AiChatSession.session_type == "mediation"
    )
    if role == "invited":
        q = q.filter(
            AiChatSession.partner_user_id == user_id,
            AiChatSession.mediation_status == "inviting",
        )
    elif role == "all":
        q = q.filter(
            (AiChatSession.user_id == user_id)
            | (AiChatSession.partner_user_id == user_id),
            AiChatSession.mediation_status != "completed",
        )
    else:  # mine
        q = q.filter(
            AiChatSession.user_id == user_id,
            AiChatSession.mediation_status != "completed",
        )
    return q.order_by(AiChatSession.created_at.desc()).all()


def get_messages_by_session(db: Session, session_id: int) -> List[AiChatMessage]:
    return (
        db.query(AiChatMessage)
        .filter(AiChatMessage.session_id == session_id)
        .order_by(AiChatMessage.created_at.asc())
        .all()
    )


def create_message(
    db: Session,
    session_id: int,
    role: str,
    content: str,
    structured_output: Optional[dict] = None,
    risk_level: Optional[str] = None,
    token_count: Optional[int] = None,
    chat_mode: Optional[str] = None,
    user_id: Optional[int] = None,
) -> AiChatMessage:
    """写消息并**同步刷新**所属 session 的 last_message_at/message_count/token_total。

    不用 onupdate：流式链路落库走独立 SessionLocal（见 _persist_streamed_message），
    必须在本函数内对 session 行做显式 UPDATE。
    token_total 口径：优先 token_count，否则 len(content) 字符数近似（禁 tiktoken）。

    `user_id`（契约 §2.4-1）：role="user" 的消息写作者，供调解按人判定 /
    按作者分组；assistant 消息与旧调用方一律保持 NULL。
    """
    msg = AiChatMessage(
        session_id=session_id,
        role=role,
        content=content,
        user_id=user_id,
        structured_output=structured_output,
        risk_level=risk_level,
        token_count=token_count,
        chat_mode=chat_mode,
    )
    db.add(msg)
    db.flush()

    session = db.query(AiChatSession).filter(AiChatSession.id == session_id).first()
    if session is not None:
        session.last_message_at = datetime.now()
        session.message_count = (session.message_count or 0) + 1
        delta_tokens = token_count if token_count is not None else len(content or "")
        session.token_total = (session.token_total or 0) + int(delta_tokens)
        db.flush()
    return msg


def create_feedback(
    db: Session,
    message_id: int,
    user_id: int,
    rating: int,
    feedback_tag: Optional[str],
    feedback_text: Optional[str],
    adopted: Optional[bool] = None,
    outcome: Optional[str] = None,
) -> AiOutputFeedback:
    fb = AiOutputFeedback(
        message_id=message_id,
        user_id=user_id,
        rating=rating,
        feedback_tag=feedback_tag,
        feedback_text=feedback_text,
        # 契约 §3.4：只增不减——旧客户端不传 → None（保持「有建议无 outcome」）
        adopted=adopted,
        outcome=outcome,
    )
    db.add(fb)
    db.flush()
    return fb


def list_pending_feedback(db: Session, user_id: int, since: datetime):
    """契约 §3.4 待回访反馈：我的会话、``outcome IS NULL``、消息在 since 之后。

    返回 ``(AiOutputFeedback, AiChatMessage, AiChatSession)`` 三元组，
    按消息时间倒序。窗口取消息时间（feedback 行无时间戳列）。
    """
    return (
        db.query(AiOutputFeedback, AiChatMessage, AiChatSession)
        .join(AiChatMessage, AiOutputFeedback.message_id == AiChatMessage.id)
        .join(AiChatSession, AiChatMessage.session_id == AiChatSession.id)
        .filter(
            AiChatSession.user_id == user_id,
            AiOutputFeedback.outcome.is_(None),
            AiChatMessage.created_at >= since,
        )
        .order_by(AiChatMessage.created_at.desc())
        .all()
    )


def delete_session(db: Session, session_id: int) -> None:
    db.query(AiChatMessage).filter(AiChatMessage.session_id == session_id).delete()
    db.query(AiChatSession).filter(AiChatSession.id == session_id).delete()
    db.commit()
