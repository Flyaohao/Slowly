from sqlalchemy.orm import Session
from datetime import date, datetime
from typing import Optional

from app.models.couple_relation import CoupleRelation
from app.models.couple_space import CoupleSpace
from app.repositories import couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def set_meet_date(db: Session, user_id: int, meet_date: date) -> dict:
    relation = _check_relation(db, user_id)
    space = couple_repo.get_space_by_relation_id(db, relation.id)
    if not space:
        raise ValueError("40001")
    space.next_meet_date = meet_date
    db.commit()
    db.refresh(space)
    return {
        "next_meet_date": space.next_meet_date.isoformat() if space.next_meet_date else None,
    }


def share_moment(db: Session, user_id: int, content: str, moment_type: str = "text", image_url: Optional[str] = None) -> dict:
    relation = _check_relation(db, user_id)
    moment = {
        "user_id": user_id,
        "relation_id": relation.id,
        "content": content,
        "moment_type": moment_type,
        "image_url": image_url,
        "created_at": datetime.utcnow().isoformat(),
    }
    return moment


def get_feed(db: Session, user_id: int) -> list:
    relation = _check_relation(db, user_id)
    return []


def send_companion_request(db: Session, user_id: int, message: Optional[str] = None) -> dict:
    relation = _check_relation(db, user_id)
    request = {
        "user_id": user_id,
        "relation_id": relation.id,
        "message": message or "我需要你陪我几分钟",
        "created_at": datetime.utcnow().isoformat(),
    }
    return request
