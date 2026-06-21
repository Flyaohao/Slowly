from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.models.couple_relation import CoupleRelation
from app.models.museum import MuseumItem
from app.repositories import museum_repo, couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def create_item(db: Session, user_id: int, data: dict) -> MuseumItem:
    relation = _check_relation(db, user_id)
    item = museum_repo.create_item(db, {
        "relation_id": relation.id,
        "item_type": data["item_type"],
        "title": data["title"],
        "story": data.get("story"),
        "source_id": data.get("source_id"),
        "source_type": data.get("source_type"),
    })
    db.commit()
    db.refresh(item)
    return item


def list_items(
    db: Session, user_id: int, item_type: Optional[str] = None,
    page: int = 1, page_size: int = 20
) -> dict:
    relation = _check_relation(db, user_id)
    items, total = museum_repo.list_items(db, relation.id, item_type, page, page_size)
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def get_item(db: Session, user_id: int, item_id: int) -> MuseumItem:
    relation = _check_relation(db, user_id)
    item = museum_repo.get_item_by_id(db, item_id)
    if not item:
        raise ValueError("80001")
    if item.relation_id != relation.id:
        raise ValueError("80002")
    return item


def update_item(db: Session, user_id: int, item_id: int, data: dict) -> MuseumItem:
    relation = _check_relation(db, user_id)
    item = museum_repo.get_item_by_id(db, item_id)
    if not item:
        raise ValueError("80001")
    if item.relation_id != relation.id:
        raise ValueError("80002")
    item = museum_repo.update_item(db, item, data)
    db.commit()
    db.refresh(item)
    return item


def delete_item(db: Session, user_id: int, item_id: int) -> None:
    relation = _check_relation(db, user_id)
    item = museum_repo.get_item_by_id(db, item_id)
    if not item:
        raise ValueError("80001")
    if item.relation_id != relation.id:
        raise ValueError("80002")
    museum_repo.delete_item(db, item)
    db.commit()


def toggle_pin(db: Session, user_id: int, item_id: int) -> MuseumItem:
    relation = _check_relation(db, user_id)
    item = museum_repo.get_item_by_id(db, item_id)
    if not item:
        raise ValueError("80001")
    if item.relation_id != relation.id:
        raise ValueError("80002")
    item.pinned = not item.pinned
    db.commit()
    db.refresh(item)
    return item
