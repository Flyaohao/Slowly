"""关系历史事件：用户手动记录，供 AI 军师引用。

本模块的重点**不是 CRUD**，而是写入门槛 —— 事件只有在写清「对这段关系起了
什么作用」之后才允许落库。理由见 `models/relationship_event.RelationshipEvent`
的 docstring：没有方向的事件对 AI 是噪声，拿它推断关系状态就是把偶发当模式。
"""
from typing import Optional, Tuple

from sqlalchemy.orm import Session

from app.models.couple_relation import CoupleRelation
from app.models.relationship_event import POLARITY_VALUES, RelationshipEvent
from app.repositories import couple_repo, relationship_event_repo
from app.schemas.relationship_event_schema import REASON_MIN_LEN


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def validate_gate(reason: Optional[str], polarity: Optional[str]) -> Tuple[str, str]:
    """**写入门槛**：原因与作用方向都合法才放行。

    刻意放在 service 而不是只写在 schema：schema 只拦得住 HTTP 入参，
    脚本、后台任务、将来的 Agent 工具都会直接调这里。门槛只要有一处能被
    绕开，「无原因事件污染军师」这条产品约束就等于没有。
    """
    text = (reason or "").strip()
    if len(text) < REASON_MIN_LEN:
        raise ValueError("20001")
    direction = (polarity or "").strip().lower()
    if direction not in POLARITY_VALUES:
        raise ValueError("20001")
    return text, direction


def serialize_event(item: RelationshipEvent) -> dict:
    return {
        "id": item.id,
        "relation_id": item.relation_id,
        "title": item.title,
        "event_time": item.event_time,
        "description": item.description,
        "reason": item.reason,
        "polarity": item.polarity,
        "created_by_user_id": item.created_by_user_id,
        "created_at": item.created_at,
    }


def create_event(db: Session, user_id: int, data: dict) -> RelationshipEvent:
    relation = _check_relation(db, user_id)
    reason, polarity = validate_gate(data.get("reason"), data.get("polarity"))
    item = relationship_event_repo.create_event(
        db,
        {
            "relation_id": relation.id,
            "title": (data.get("title") or "").strip(),
            "event_time": data.get("event_time"),
            "description": (data.get("description") or "").strip() or None,
            "reason": reason,
            "polarity": polarity,
            "created_by_user_id": user_id,
        },
    )
    db.commit()
    db.refresh(item)
    return item


def list_events(db: Session, user_id: int, page: int, page_size: int) -> dict:
    relation = _check_relation(db, user_id)
    items, total = relationship_event_repo.list_events(
        db, relation.id, page, page_size
    )
    return {"total": total, "items": items}


def get_event(db: Session, user_id: int, event_id: int) -> RelationshipEvent:
    relation = _check_relation(db, user_id)
    item = relationship_event_repo.get_event_by_id(db, event_id)
    if item is None or item.relation_id != relation.id:
        raise ValueError("100001")
    return item


def update_event(
    db: Session, user_id: int, event_id: int, data: dict
) -> RelationshipEvent:
    item = get_event(db, user_id, event_id)
    # 只要本次动到 reason / polarity 中的任意一个，就整体重过门槛：
    # 允许「只改原因、不重报方向」会留下 reason 与 polarity 互相矛盾的行，
    # 而 AI 引用时读的正是这两个字段的组合。
    if "reason" in data or "polarity" in data:
        reason, polarity = validate_gate(
            data.get("reason", item.reason),
            data.get("polarity", item.polarity),
        )
        data = dict(data)
        data["reason"], data["polarity"] = reason, polarity
    item = relationship_event_repo.update_event(db, item, data)
    db.commit()
    db.refresh(item)
    return item


def delete_event(db: Session, user_id: int, event_id: int) -> None:
    item = get_event(db, user_id, event_id)
    relationship_event_repo.delete_event(db, item)
    db.commit()
