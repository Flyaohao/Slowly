from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.models.couple_relation import CoupleRelation
from app.models.practice import RelationshipPractice, PracticeRecord
from app.repositories import practice_repo, couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def list_practices(db: Session) -> List[RelationshipPractice]:
    return practice_repo.list_practices(db)


def start_practice(db: Session, user_id: int, practice_id: int) -> PracticeRecord:
    relation = _check_relation(db, user_id)
    practice = practice_repo.get_practice_by_id(db, practice_id)
    if not practice:
        raise ValueError("90001")

    record = practice_repo.create_record(db, {
        "practice_id": practice_id,
        "relation_id": relation.id,
        "initiator_id": user_id,
        "status": "initiated",
    })
    db.commit()
    db.refresh(record)
    return record


def submit_practice(db: Session, user_id: int, record_id: int, data: dict) -> PracticeRecord:
    relation = _check_relation(db, user_id)
    record = practice_repo.get_record_by_id(db, record_id)
    if not record:
        raise ValueError("90002")
    if record.relation_id != relation.id:
        raise ValueError("90003")

    if record.status == "initiated":
        record.status = "both_completed"
    elif record.status == "both_completed":
        record.status = "summarized"
        record.summary = data.get("content", "")

    db.commit()
    db.refresh(record)
    return record


def list_records(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    relation = _check_relation(db, user_id)
    items, total = practice_repo.list_records(db, relation.id, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def get_record(db: Session, user_id: int, record_id: int) -> PracticeRecord:
    relation = _check_relation(db, user_id)
    record = practice_repo.get_record_by_id(db, record_id)
    if not record:
        raise ValueError("90002")
    if record.relation_id != relation.id:
        raise ValueError("90003")
    return record
