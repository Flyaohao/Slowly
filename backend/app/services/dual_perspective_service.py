from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.models.couple_relation import CoupleRelation
from app.models.dual_perspective import DualPerspectiveEvent, DualPerspectiveRecord
from app.repositories import dual_perspective_repo, couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def _get_partner_id(relation: CoupleRelation, user_id: int) -> int:
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def create_event(db: Session, user_id: int, data: dict) -> DualPerspectiveEvent:
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.create_event(db, {
        "relation_id": relation.id,
        "title": data["title"],
        "event_time": data["event_time"],
        "status": "one_side",
    })
    db.commit()
    db.refresh(event)
    return event


def list_events(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    relation = _check_relation(db, user_id)
    items, total = dual_perspective_repo.list_events(db, relation.id, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def get_event_detail(db: Session, user_id: int, event_id: int) -> DualPerspectiveEvent:
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.get_event_by_id(db, event_id)
    if not event:
        raise ValueError("70001")
    if event.relation_id != relation.id:
        raise ValueError("70002")
    records = dual_perspective_repo.get_records_by_event(db, event_id)
    event.records = records
    return event


def submit_record(db: Session, user_id: int, event_id: int, data: dict) -> DualPerspectiveRecord:
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.get_event_by_id(db, event_id)
    if not event:
        raise ValueError("70001")
    if event.relation_id != relation.id:
        raise ValueError("70002")

    existing = dual_perspective_repo.get_record_by_event_and_user(db, event_id, user_id)
    if existing:
        raise ValueError("70003")

    record = dual_perspective_repo.create_record(db, {
        "event_id": event_id,
        "user_id": user_id,
        "content": data["content"],
        "visibility": data.get("visibility", "hidden"),
    })

    partner_id = _get_partner_id(relation, user_id)
    partner_record = dual_perspective_repo.get_record_by_event_and_user(db, event_id, partner_id)
    if partner_record:
        event.status = "both_sides"
    else:
        event.status = "one_side"

    db.commit()
    db.refresh(record)
    return record


def edit_record(db: Session, user_id: int, event_id: int, record_id: int, data: dict) -> DualPerspectiveRecord:
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.get_event_by_id(db, event_id)
    if not event:
        raise ValueError("70001")
    if event.relation_id != relation.id:
        raise ValueError("70002")

    record = dual_perspective_repo.get_record_by_id(db, record_id)
    if not record:
        raise ValueError("70004")
    if record.user_id != user_id:
        raise ValueError("70002")
    if record.event_id != event_id:
        raise ValueError("70004")

    record = dual_perspective_repo.update_record(db, record, data)
    db.commit()
    db.refresh(record)
    return record


def reveal_event(db: Session, user_id: int, event_id: int) -> DualPerspectiveEvent:
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.get_event_by_id(db, event_id)
    if not event:
        raise ValueError("70001")
    if event.relation_id != relation.id:
        raise ValueError("70002")
    if event.status != "both_sides":
        raise ValueError("70005")

    records = dual_perspective_repo.get_records_by_event(db, event_id)
    for record in records:
        if record.visibility == "hidden":
            record.visibility = "visible"

    event.status = "completed"
    db.commit()
    db.refresh(event)
    return event
