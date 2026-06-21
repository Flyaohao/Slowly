from sqlalchemy.orm import Session
from sqlalchemy import and_
from typing import Optional, List
from datetime import datetime

from app.models.letter import Letter


def create_letter(db: Session, data: dict) -> Letter:
    letter = Letter(**data)
    db.add(letter)
    db.flush()
    return letter


def get_letter_by_id(db: Session, letter_id: int) -> Optional[Letter]:
    return (
        db.query(Letter)
        .filter(Letter.id == letter_id, Letter.deleted_at.is_(None))
        .first()
    )


# Fields allowed to be updated by the client
UPDATABLE_FIELDS = {"title", "content", "letter_type", "is_private", "is_favorite"}


def update_letter(db: Session, letter: Letter, data: dict) -> Letter:
    for key, value in data.items():
        if key in UPDATABLE_FIELDS and value is not None:
            setattr(letter, key, value)
    db.flush()
    return letter


def soft_delete_letter(db: Session, letter: Letter) -> Letter:
    letter.deleted_at = datetime.utcnow()
    db.flush()
    return letter


def list_letters(
    db: Session,
    relation_id: Optional[int] = None,
    user_id: Optional[int] = None,
    letter_type: Optional[str] = None,
    status: Optional[str] = None,
    direction: Optional[str] = None,
    is_favorite: Optional[bool] = None,
    inbox: bool = False,
    drafts: bool = False,
    page: int = 1,
    page_size: int = 20,
) -> tuple:
    query = db.query(Letter).filter(Letter.deleted_at.is_(None))

    if relation_id is not None:
        query = query.filter(Letter.relation_id == relation_id)
    else:
        query = query.filter(Letter.relation_id.is_(None), Letter.sender_id == user_id)

    if inbox:
        query = query.filter(Letter.receiver_id == user_id, Letter.status != "draft")
    elif drafts:
        query = query.filter(Letter.sender_id == user_id, Letter.status == "draft")
    elif direction == "sent":
        query = query.filter(Letter.sender_id == user_id, Letter.status != "draft")
    elif direction == "received":
        query = query.filter(Letter.receiver_id == user_id, Letter.status != "draft")
    elif user_id is not None:
        query = query.filter(
            (Letter.sender_id == user_id) | (Letter.receiver_id == user_id)
        )

    if letter_type:
        query = query.filter(Letter.letter_type == letter_type)
    if status:
        query = query.filter(Letter.status == status)
    if is_favorite is not None:
        query = query.filter(Letter.is_favorite == is_favorite)

    total = query.count()
    items = (
        query.order_by(Letter.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total
