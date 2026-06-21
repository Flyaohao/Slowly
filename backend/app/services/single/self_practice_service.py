from sqlalchemy.orm import Session
from typing import List

from app.models.self_practice import SelfPractice, SelfPracticeRecord
from app.repositories import self_practice_repo


def list_practices(db: Session) -> List[SelfPractice]:
    return self_practice_repo.list_practices(db)


def start_practice(db: Session, user_id: int, practice_id: int) -> SelfPracticeRecord:
    practice = self_practice_repo.get_practice_by_id(db, practice_id)
    if not practice:
        raise ValueError("90001")

    record = self_practice_repo.create_record(db, {
        "practice_id": practice_id,
        "user_id": user_id,
        "status": "started",
    })
    db.commit()
    db.refresh(record)
    return record


def submit_practice(
    db: Session, user_id: int, record_id: int, data: dict
) -> SelfPracticeRecord:
    record = self_practice_repo.get_record_by_id(db, record_id)
    if not record:
        raise ValueError("90002")
    if record.user_id != user_id:
        raise ValueError("90003")

    record.status = "completed"
    record.content = data.get("content")
    record.reflection = data.get("reflection")
    db.commit()
    db.refresh(record)
    return record


def list_records(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    items, total = self_practice_repo.list_records(db, user_id, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def get_record(db: Session, user_id: int, record_id: int) -> SelfPracticeRecord:
    record = self_practice_repo.get_record_by_id(db, record_id)
    if not record:
        raise ValueError("90002")
    if record.user_id != user_id:
        raise ValueError("90003")
    return record
