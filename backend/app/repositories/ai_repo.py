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
) -> AiChatSession:
    session = AiChatSession(
        user_id=user_id,
        relation_id=relation_id,
        scene_key=scene_key,
        title=title,
        privacy_level=privacy_level,
    )
    db.add(session)
    db.flush()
    return session


def get_session_by_id(db: Session, session_id: int) -> Optional[AiChatSession]:
    return db.query(AiChatSession).filter(AiChatSession.id == session_id).first()


def get_sessions_by_user(db: Session, user_id: int) -> List[AiChatSession]:
    return (
        db.query(AiChatSession)
        .filter(AiChatSession.user_id == user_id)
        .order_by(AiChatSession.updated_at.desc())
        .all()
    )


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
) -> AiChatMessage:
    msg = AiChatMessage(
        session_id=session_id,
        role=role,
        content=content,
        structured_output=structured_output,
        risk_level=risk_level,
        token_count=token_count,
    )
    db.add(msg)
    db.flush()
    return msg


def create_feedback(
    db: Session,
    message_id: int,
    user_id: int,
    rating: int,
    feedback_tag: Optional[str],
    feedback_text: Optional[str],
) -> AiOutputFeedback:
    fb = AiOutputFeedback(
        message_id=message_id,
        user_id=user_id,
        rating=rating,
        feedback_tag=feedback_tag,
        feedback_text=feedback_text,
    )
    db.add(fb)
    db.flush()
    return fb


def delete_session(db: Session, session_id: int) -> None:
    db.query(AiChatMessage).filter(AiChatMessage.session_id == session_id).delete()
    db.query(AiChatSession).filter(AiChatSession.id == session_id).delete()
    db.commit()
