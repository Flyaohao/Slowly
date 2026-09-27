"""关系事件的数据访问层（只做 SQLAlchemy 访问，不提交事务）。"""
from typing import List, Optional, Tuple

from sqlalchemy.orm import Session

from app.models.relationship_event import RelationshipEvent


def create_event(db: Session, data: dict) -> RelationshipEvent:
    item = RelationshipEvent(**data)
    db.add(item)
    db.flush()
    return item


def get_event_by_id(db: Session, event_id: int) -> Optional[RelationshipEvent]:
    return (
        db.query(RelationshipEvent)
        .filter(RelationshipEvent.id == event_id)
        .first()
    )


def list_events(
    db: Session, relation_id: int, page: int = 1, page_size: int = 20
) -> Tuple[List[RelationshipEvent], int]:
    """按发生时间倒序：最近发生的事最先看到。"""
    query = db.query(RelationshipEvent).filter(
        RelationshipEvent.relation_id == relation_id
    )
    total = query.count()
    items = (
        query.order_by(RelationshipEvent.event_time.desc(), RelationshipEvent.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def update_event(
    db: Session, item: RelationshipEvent, data: dict
) -> RelationshipEvent:
    for key, value in data.items():
        if value is not None:
            setattr(item, key, value)
    db.flush()
    return item


def delete_event(db: Session, item: RelationshipEvent) -> None:
    db.delete(item)
    db.flush()
