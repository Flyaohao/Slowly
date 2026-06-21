from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.self_practice import SelfPractice, SelfPracticeRecord


def list_practices(db: Session) -> List[SelfPractice]:
    return (
        db.query(SelfPractice)
        .filter(SelfPractice.is_active == True)
        .order_by(SelfPractice.id)
        .all()
    )


def get_practice_by_id(db: Session, practice_id: int) -> Optional[SelfPractice]:
    return db.query(SelfPractice).filter(SelfPractice.id == practice_id).first()


def create_record(db: Session, data: dict) -> SelfPracticeRecord:
    record = SelfPracticeRecord(**data)
    db.add(record)
    db.flush()
    return record


def get_record_by_id(db: Session, record_id: int) -> Optional[SelfPracticeRecord]:
    return db.query(SelfPracticeRecord).filter(SelfPracticeRecord.id == record_id).first()


def list_records(
    db: Session, user_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    query = (
        db.query(SelfPracticeRecord)
        .filter(SelfPracticeRecord.user_id == user_id)
        .order_by(SelfPracticeRecord.created_at.desc())
    )
    total = query.count()
    items = query.offset((page - 1) * page_size).limit(page_size).all()
    return items, total
