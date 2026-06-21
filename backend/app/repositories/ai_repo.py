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


def get_active_prompt_template(db: Session, scene_key: str) -> Optional[AiPromptTemplate]:
    return (
        db.query(AiPromptTemplate)
        .filter(
            AiPromptTemplate.scene_key == scene_key,
            AiPromptTemplate.status == "active",
        )
        .order_by(AiPromptTemplate.version.desc())
        .first()
    )


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
