from typing import Optional, List, Dict
from datetime import datetime

from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.models.ai import AiMemory


def create_memory(
    db: Session,
    user_id: int,
    relation_id: int,
    memory_type: str,
    memory_text: str,
    visibility: str = "private",
) -> dict:
    memory = AiMemory(
        user_id=user_id,
        relation_id=relation_id,
        memory_type=memory_type,
        memory_text=memory_text,
        visibility=visibility,
    )
    db.add(memory)
    db.flush()
    db.commit()
    return _to_dict(memory)


def get_memories(
    db: Session,
    user_id: int,
    relation_id: Optional[int] = None,
    memory_type: Optional[str] = None,
) -> List[dict]:
    query = db.query(AiMemory).filter(AiMemory.user_id == user_id)
    if relation_id:
        query = query.filter(AiMemory.relation_id == relation_id)
    if memory_type:
        query = query.filter(AiMemory.memory_type == memory_type)
    memories = query.order_by(AiMemory.created_at.desc()).all()
    return [_to_dict(m) for m in memories]


def get_couple_memories(db: Session, user_id: int, relation_id: int) -> List[dict]:
    memories = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.visibility == "couple",
            )
        )
        .order_by(AiMemory.created_at.desc())
        .all()
    )
    return [_to_dict(m) for m in memories]


def get_memory_context(db: Session, user_id: int, relation_id: int, limit: int = 10) -> str:
    memories = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.user_id == user_id,
                AiMemory.relation_id == relation_id,
            )
        )
        .order_by(AiMemory.created_at.desc())
        .limit(limit)
        .all()
    )
    if not memories:
        return ""
    lines = [f"- [{m.memory_type}] {m.memory_text}" for m in memories]
    return "## AI 记忆\n" + "\n".join(lines)


def update_visibility(db: Session, memory_id: int, user_id: int, visibility: str) -> dict:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    memory.visibility = visibility
    db.commit()
    return _to_dict(memory)


def delete_memory(db: Session, memory_id: int, user_id: int) -> None:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    db.delete(memory)
    db.commit()


def _to_dict(memory: AiMemory) -> dict:
    return {
        "id": memory.id,
        "user_id": memory.user_id,
        "relation_id": memory.relation_id,
        "memory_type": memory.memory_type,
        "memory_text": memory.memory_text,
        "visibility": memory.visibility,
        "created_at": memory.created_at.isoformat() if memory.created_at else None,
    }
