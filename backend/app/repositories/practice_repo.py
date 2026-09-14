from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.practice import RelationshipPractice, PracticeRecord


def list_practices(db: Session) -> List[RelationshipPractice]:
    return db.query(RelationshipPractice).all()


def get_practice_by_id(db: Session, practice_id: int) -> Optional[RelationshipPractice]:
    return db.query(RelationshipPractice).filter(RelationshipPractice.id == practice_id).first()


def create_record(db: Session, data: dict) -> PracticeRecord:
    record = PracticeRecord(**data)
    db.add(record)
    db.flush()
    return record


def get_record_by_id(db: Session, record_id: int) -> Optional[PracticeRecord]:
    return db.query(PracticeRecord).filter(PracticeRecord.id == record_id).first()


def list_records(
    db: Session, relation_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    query = db.query(PracticeRecord).filter(PracticeRecord.relation_id == relation_id)
    total = query.count()
    items = (
        query.order_by(PracticeRecord.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def list_records_by_practice(
    db: Session, practice_id: int, relation_id: int
) -> List[PracticeRecord]:
    """取出同一练习下、同一情侣关系内的全部提交记录（用于展示双方内容）。"""
    return (
        db.query(PracticeRecord)
        .filter(
            PracticeRecord.practice_id == practice_id,
            PracticeRecord.relation_id == relation_id,
        )
        .all()
    )


def update_record(db: Session, record: PracticeRecord, data: dict) -> PracticeRecord:
    for key, value in data.items():
        if value is not None:
            setattr(record, key, value)
    db.flush()
    return record
