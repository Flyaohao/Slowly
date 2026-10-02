from sqlalchemy.orm import Session
from typing import Optional, List
from datetime import date, datetime

from app.models.couple_relation import CoupleRelation
from app.models.anniversary import Anniversary, Wishlist
from app.repositories import anniversary_repo, couple_repo
from app.services.anniversary_dates import next_occurrence


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def serialize_anniversary(item: Anniversary, today: Optional[date] = None) -> dict:
    """纪念日出参：附上**服务端算好的**下次发生日与剩余天数（契约 §8.8）。

    单一出口，列表和今后的详情都走这里——两端各算一遍必然算出两个答案。
    一次性的、已经过去的纪念日 `next_occurrence_date` / `days_until` 都是 None，
    客户端据此显示原始日期，而不是硬凑一个「还有 N 天」。
    """
    today = today or date.today()
    repeat = bool(getattr(item, "repeat_annually", True))
    occurrence = next_occurrence(item.anniversary_date, repeat, today)
    return {
        "id": item.id,
        "relation_id": item.relation_id,
        "title": item.title,
        "anniversary_date": item.anniversary_date,
        "repeat_annually": repeat,
        "description": item.description,
        "created_at": item.created_at,
        "next_occurrence_date": occurrence,
        "days_until": None if occurrence is None else (occurrence - today).days,
    }


def create_anniversary(db: Session, user_id: int, data: dict) -> Anniversary:
    relation = _check_relation(db, user_id)
    item = anniversary_repo.create_anniversary(db, {
        "relation_id": relation.id,
        "title": data["title"],
        "anniversary_date": data["anniversary_date"],
        # 契约 §8.8：缺省按「每年重复」——存量语义不变
        "repeat_annually": data.get("repeat_annually", True),
        "description": data.get("description"),
    })
    db.commit()
    db.refresh(item)

    # P0-3：纪念日不经 AI，结构化直写（名称+日期），复用同一套去重。
    # 一次性纪念日不能说成「每年 X 月 X 日」，否则记忆里也会留下年份冲突。
    from app.services.memory_events import MemoryEvent, distill_event_in_background

    repeat = bool(item.repeat_annually)
    when = (
        f"每年 {item.anniversary_date.month} 月 {item.anniversary_date.day} 日"
        if repeat
        else f"{item.anniversary_date.isoformat()}（一次性）"
    )
    distill_event_in_background(MemoryEvent(
        source="anniversary",
        source_id=item.id,
        user_id=user_id,
        relation_id=relation.id,
        content=f"{item.title}是 {when}",
        occurred_at=datetime.utcnow(),
        extra={"context": f"纪念日：{item.title}"},
    ))
    return item


def list_anniversaries(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    relation = _check_relation(db, user_id)
    items, total = anniversary_repo.list_anniversaries(db, relation.id, page, page_size)
    return {
        "items": [serialize_anniversary(i) for i in items],
        "total": total,
        "page": page,
        "page_size": page_size,
    }


def get_anniversary(db: Session, user_id: int, item_id: int) -> Anniversary:
    """取单条纪念日。

    与 update/delete 同一套鉴权口径：先确认绑定了情侣，再比relation_id。
    **顺序不能反**——先比 relation_id 会把「没绑定」和「无权访问他人」混成同一个码，
    前者该回30005（引导去绑定），后者该回 100002。
    """
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_anniversary_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    return item


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


def get_wishlist(db: Session, user_id: int, item_id: int) -> Wishlist:
    """取单条愿望。鉴权口径与 get_anniversary 一致：先绑定、再比 relation_id。"""
    relation = _check_relation(db, user_id)
    item = anniversary_repo.get_wishlist_by_id(db, item_id)
    if not item:
        raise ValueError("100001")
    if item.relation_id != relation.id:
        raise ValueError("100002")
    return item


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
