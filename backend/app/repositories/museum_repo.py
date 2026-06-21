from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.museum import MuseumItem


def create_item(db: Session, data: dict) -> MuseumItem:
    item = MuseumItem(**data)
    db.add(item)
    db.flush()
    return item


def get_item_by_id(db: Session, item_id: int) -> Optional[MuseumItem]:
    return db.query(MuseumItem).filter(MuseumItem.id == item_id).first()


def list_items(
    db: Session,
    relation_id: int,
    item_type: Optional[str] = None,
    page: int = 1,
    page_size: int = 20,
) -> tuple:
    query = db.query(MuseumItem).filter(MuseumItem.relation_id == relation_id)
    if item_type:
        query = query.filter(MuseumItem.item_type == item_type)
    total = query.count()
    items = (
        query.order_by(MuseumItem.pinned.desc(), MuseumItem.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def update_item(db: Session, item: MuseumItem, data: dict) -> MuseumItem:
    for key, value in data.items():
        if value is not None:
            setattr(item, key, value)
    db.flush()
    return item


def delete_item(db: Session, item: MuseumItem) -> None:
    db.delete(item)
    db.flush()
