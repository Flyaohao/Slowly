from sqlalchemy.orm import Session
from typing import Optional, List
from datetime import datetime

from app.models.couple_relation import CoupleRelation
from app.models.anniversary import Anniversary, Wishlist
from app.repositories import anniversary_repo, couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def create_anniversary(db: Session, user_id: int, data: dict) -> Anniversary:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.create_anniversary(db, {
        "relation_id": relation.id,
        "title": data["title"],
        "anniversary_date": data["anniversary_date"],
        "description": data.get("description"),
    })
    db.commit()
    db.refresh(item)
    return item


def list_anniversaries(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    relation = _check_relation(db, user_id)
    items, total = anniversary_repo.list_anniversaries(db, relation.id, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def update_anniversary(db: Session, user_id: int, item_id: int, data: dict) -> Anniversary:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_anniversary_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    item = anniversary_repo.update_anniversary(db, item, data)
    db.commit()
    db.refresh(item)
    return item


def delete_anniversary(db: Session, user_id: int, item_id: int) -> None:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_anniversary_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    anniversary_repo.delete_anniversary(db, item)
    db.commit()


def create_wishlist(db: Session, user_id: int, data: dict) -> Wishlist:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.create_wishlist(db, {
        "relation_id": relation.id,
        "title": data["title"],
        "description": data.get("description"),
    })
    db.commit()
    db.refresh(item)
    return item


def list_wishlists(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    relation = _check_relation(db, user_id)
    items, total = anniversary_repo.list_wishlists(db, relation.id, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def update_wishlist(db: Session, user_id: int, item_id: int, data: dict) -> Wishlist:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_wishlist_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    item = anniversary_repo.update_wishlist(db, item, data)
    db.commit()
    db.refresh(item)
    return item


def complete_wishlist(db: Session, user_id: int, item_id: int) -> Wishlist:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_wishlist_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    item.status = "completed"
    item.completed_at = datetime.utcnow()
    db.commit()
    db.refresh(item)
    return item


def delete_wishlist(db: Session, user_id: int, item_id: int) -> None:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_wishlist_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    anniversary_repo.delete_wishlist(db, item)
    db.commit()
