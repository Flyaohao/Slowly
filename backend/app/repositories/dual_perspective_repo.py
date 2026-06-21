from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.dual_perspective import DualPerspectiveEvent, DualPerspectiveRecord


def create_event(db: Session, data: dict) -> DualPerspectiveEvent:
    event = DualPerspectiveEvent(**data)
    db.add(event)
    db.flush()
    return event


def get_event_by_id(db: Session, event_id: int) -> Optional[DualPerspectiveEvent]:
    return db.query(DualPerspectiveEvent).filter(DualPerspectiveEvent.id == event_id).first()


def list_events(
    db: Session, relation_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    query = db.query(DualPerspectiveEvent).filter(
        DualPerspectiveEvent.relation_id == relation_id
    )
    total = query.count()
    items = (
        query.order_by(DualPerspectiveEvent.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def update_event(db: Session, event: DualPerspectiveEvent, data: dict) -> DualPerspectiveEvent:
    for key, value in data.items():
        if value is not None:
            setattr(event, key, value)
    db.flush()
    return event


def create_record(db: Session, data: dict) -> DualPerspectiveRecord:
    record = DualPerspectiveRecord(**data)
    db.add(record)
    db.flush()
    return record


def get_record_by_id(db: Session, record_id: int) -> Optional[DualPerspectiveRecord]:
    return db.query(DualPerspectiveRecord).filter(DualPerspectiveRecord.id == record_id).first()


def get_records_by_event(db: Session, event_id: int) -> List[DualPerspectiveRecord]:
    return (
        db.query(DualPerspectiveRecord)
        .filter(DualPerspectiveRecord.event_id == event_id)
        .all()
    )


def get_record_by_event_and_user(
    db: Session, event_id: int, user_id: int
) -> Optional[DualPerspectiveRecord]:
    return (
        db.query(DualPerspectiveRecord)
        .filter(
            DualPerspectiveRecord.event_id == event_id,
            DualPerspectiveRecord.user_id == user_id,
        )
        .first()
    )


def update_record(db: Session, record: DualPerspectiveRecord, data: dict) -> DualPerspectiveRecord:
    for key, value in data.items():
        if value is not None:
            setattr(record, key, value)
    db.flush()
    return record
