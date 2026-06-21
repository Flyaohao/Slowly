from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.anniversary import Anniversary, Wishlist


def create_anniversary(db: Session, data: dict) -> Anniversary:
    item = Anniversary(**data)
    db.add(item)
    db.flush()
    return item


def get_anniversary_by_id(db: Session, item_id: int) -> Optional[Anniversary]:
    return db.query(Anniversary).filter(Anniversary.id == item_id).first()


def list_anniversaries(
    db: Session, relation_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    query = db.query(Anniversary).filter(Anniversary.relation_id == relation_id)
    total = query.count()
    items = (
        query.order_by(Anniversary.anniversary_date.asc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def update_anniversary(db: Session, item: Anniversary, data: dict) -> Anniversary:
    for key, value in data.items():
        if value is not None:
            setattr(item, key, value)
    db.flush()
    return item


def delete_anniversary(db: Session, item: Anniversary) -> None:
    db.delete(item)
    db.flush()


def create_wishlist(db: Session, data: dict) -> Wishlist:
    item = Wishlist(**data)
    db.add(item)
    db.flush()
    return item


def get_wishlist_by_id(db: Session, item_id: int) -> Optional[Wishlist]:
    return db.query(Wishlist).filter(Wishlist.id == item_id).first()


def list_wishlists(
    db: Session, relation_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    query = db.query(Wishlist).filter(Wishlist.relation_id == relation_id)
    total = query.count()
    items = (
        query.order_by(Wishlist.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def update_wishlist(db: Session, item: Wishlist, data: dict) -> Wishlist:
    for key, value in data.items():
        if value is not None:
            setattr(item, key, value)
    db.flush()
    return item


def delete_wishlist(db: Session, item: Wishlist) -> None:
    db.delete(item)
    db.flush()
