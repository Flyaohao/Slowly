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
    """事件详情（契约 §2.1-1：**服务端可见性过滤**）。

    viewer 只拿到「自己的 record + `visibility == visible` 的对方 record」；
    对方已提交但未公开时，内容整段不返回，只在响应里给 `partner_submitted=true`
    ——泄露面从「UI 没渲染」收紧到「响应里根本没有」。

    records 排序固定：先本人、再对方，各按 id 升序（原顺序不定，
    客户端 `records.first()` 可能把对方行当成"我的"）。
    """
    relation = _check_relation(db, user_id)
    event = dual_perspective_repo.get_event_by_id(db, event_id)
    if not event:
        raise ValueError("70001")
    if event.relation_id != relation.id:
        raise ValueError("70002")
    partner_id = _get_partner_id(relation, user_id)

    records = dual_perspective_repo.get_records_by_event(db, event_id)
    visible = [
        r for r in records
        if r.user_id == user_id or r.visibility == "visible"
    ]
    visible.sort(key=lambda r: (0 if r.user_id == user_id else 1, r.id))

    event.records = visible
    # 只增不减的新字段：对方是否已提交（不暴露其内容）
    event.partner_submitted = any(r.user_id == partner_id for r in records)
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
        # 契约 §2.1-2：提交时服务端一律置 hidden，**忽略客户端传入的 visibility**。
        # 只有 reveal 能翻成 visible。
        "visibility": "hidden",
    })

    partner_id = _get_partner_id(relation, user_id)
    partner_record = dual_perspective_repo.get_record_by_event_and_user(db, event_id, partner_id)
    if partner_record:
        event.status = "both_sides"
    else:
        event.status = "one_side"

    db.commit()
    db.refresh(record)
    # 契约 §2.1-5：蒸馏时机已移到 reveal_event 之后——reveal 前绝不写入记忆。
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

    # 契约 §2.1-2：客户端传入的 visibility 一律忽略。
    # 已 reveal（completed）的事件保持 visible（reveal 是唯一的翻转开关），
    # 未 reveal 的一律回到 hidden——客户端无论如何都**不能**把自己改成可见。
    data = dict(data)
    data.pop("visibility", None)
    data["visibility"] = "visible" if event.status == "completed" else "hidden"

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
    event.partner_submitted = True

    # 契约 §2.1-5：蒸馏时机 = reveal 成功之后（原来在第二方提交时就双写，
    # 导致 reveal 前伴侣能从记忆列表读到原文）。这里只挪调用时机，
    # memory_events 的内部语义一行未动（禁碰区声明见执行记录）。
    my_record = next((r for r in records if r.user_id == user_id), None)
    partner_record = next((r for r in records if r.user_id != user_id), None)
    if my_record and partner_record:
        from datetime import datetime
        from app.services.memory_events import MemoryEvent, distill_event_in_background

        distill_event_in_background(MemoryEvent(
            source="dual",
            source_id=event.id,
            user_id=user_id,
            relation_id=relation.id,
            content=(
                f"事件：{event.title}\n"
                f"我方记录：{my_record.content}\n"
                f"对方记录：{partner_record.content}"
            ),
            occurred_at=datetime.utcnow(),
            extra={"context": f"双视角：{event.title}"},
        ))
    return event
