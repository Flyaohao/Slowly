from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime, timedelta

from app.core.config import MEMORY_PURGE_RETENTION_DAYS
from app.models.couple_relation import CoupleRelation
from app.models.couple_space import CoupleSpace


def get_active_relation_by_user(db: Session, user_id: int) -> Optional[CoupleRelation]:
    return (
        db.query(CoupleRelation)
        .filter(
            CoupleRelation.status == "active",
            (CoupleRelation.user_a_id == user_id) | (CoupleRelation.user_b_id == user_id),
        )
        .first()
    )


def get_relation_by_user_including_unbinding(db: Session, user_id: int) -> Optional[CoupleRelation]:
    """获取用户的情侣关系（包括 active 和 unbinding 状态）"""
    return (
        db.query(CoupleRelation)
        .filter(
            CoupleRelation.status.in_(["active", "unbinding"]),
            (CoupleRelation.user_a_id == user_id) | (CoupleRelation.user_b_id == user_id),
        )
        .first()
    )


def get_relation_by_id(db: Session, relation_id: int) -> Optional[CoupleRelation]:
    return db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()


def create_relation(db: Session, user_a_id: int, user_b_id: int) -> CoupleRelation:
    relation = CoupleRelation(
        user_a_id=user_a_id,
        user_b_id=user_b_id,
        status="active",
        bind_time=datetime.utcnow(),
    )
    db.add(relation)
    db.flush()
    space = CoupleSpace(relation_id=relation.id)
    db.add(space)
    db.commit()
    db.refresh(relation)
    return relation


def get_space_by_relation_id(db: Session, relation_id: int) -> Optional[CoupleSpace]:
    return db.query(CoupleSpace).filter(CoupleSpace.relation_id == relation_id).first()


def update_space(db: Session, relation_id: int, data: dict) -> Optional[CoupleSpace]:
    space = get_space_by_relation_id(db, relation_id)
    if space is None:
        return None
    for key, value in data.items():
        if value is not None:
            setattr(space, key, value)
    db.commit()
    db.refresh(space)
    return space


def request_unbind(db: Session, relation_id: int, user_id: int) -> Optional[CoupleRelation]:
    relation = get_relation_by_id(db, relation_id)
    if relation is None:
        return None
    relation.status = "unbinding"
    relation.unbind_requested_by = user_id
    relation.unbind_requested_at = datetime.utcnow()
    db.commit()
    db.refresh(relation)
    return relation


def confirm_unbind(db: Session, relation_id: int) -> Optional[CoupleRelation]:
    relation = get_relation_by_id(db, relation_id)
    if relation is None:
        return None
    relation.status = "dissolved"
    relation.unbind_confirmed_at = datetime.utcnow()
    # 记忆治理（记忆系统升级 P0⑥）：解绑落定即排定清理时刻 = now + 保留期。
    # 到期且无 legal_hold 由 memory_purge_service 执行 purge（DB + Chroma）；
    # 保留期内记忆仍在库里（管理读可见、AI 召回已被 active 硬边界挡住）。
    relation.memory_purge_after = datetime.utcnow() + timedelta(
        days=MEMORY_PURGE_RETENTION_DAYS
    )
    db.commit()
    db.refresh(relation)
    return relation


def cancel_unbind(db: Session, relation_id: int) -> Optional[CoupleRelation]:
    relation = get_relation_by_id(db, relation_id)
    if relation is None or relation.status != "unbinding":
        return None
    relation.status = "active"
    relation.unbind_requested_by = None
    relation.unbind_requested_at = None
    # 撤销解绑 = 撤销解绑排定的清理时刻（否则旧时刻残留，虽然 purge 只认
    # dissolved 状态不会误删，但留着是脏数据）
    relation.memory_purge_after = None
    db.commit()
    db.refresh(relation)
    return relation
