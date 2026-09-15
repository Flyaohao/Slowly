from sqlalchemy.orm import Session
from datetime import date
from typing import Optional

from app.models.couple_relation import CoupleRelation
from app.models.couple_space import CoupleSpace
from app.repositories import couple_repo, presence_repo
from app.services.notification_service import notify_partner_moment


def _notify_partner_async(partner_id: int, user_id: int, moment_type: str, content: str) -> None:
    """把实时通知丢进事件循环；同步上下文里兜底起新循环。

    与 couple_service 的解绑通知同一套模式：通知失败绝不影响落库主链路。
    """
    import asyncio

    async def _run():
        try:
            await notify_partner_moment(partner_id, user_id, moment_type, content)
        except Exception:
            pass

    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.ensure_future(_run())
        else:
            loop.run_until_complete(_run())
    except Exception:
        pass


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def _moment_out(moment) -> dict:
    return {
        "id": moment.id,
        "user_id": moment.user_id,
        "moment_type": moment.moment_type,
        "content": moment.content,
        "image_url": moment.image_url,
        "created_at": moment.created_at.isoformat() if moment.created_at else None,
    }


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


def share_moment(db: Session, user_id: int, content: str, moment_type: str = "text",
                 image_url: Optional[str] = None) -> dict:
    """分享此刻状态（落库，双方 feed 可见）。"""
    relation = _check_relation(db, user_id)
    moment = presence_repo.create_moment(
        db, relation.id, user_id, moment_type or "text", content, image_url,
    )
    partner_id = (
        relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
    )
    if partner_id:
        _notify_partner_async(partner_id, user_id, moment.moment_type, content)
    return _moment_out(moment)


def get_feed(db: Session, user_id: int) -> list:
    """最近 20 条动态（双方共用一条时间线）。"""
    relation = _check_relation(db, user_id)
    return [_moment_out(m) for m in presence_repo.list_recent(db, relation.id)]


def send_companion_request(db: Session, user_id: int, message: Optional[str] = None) -> dict:
    """陪伴请求：作为一条特殊动态落库，对方在 feed 里能看到。"""
    relation = _check_relation(db, user_id)
    moment = presence_repo.create_moment(
        db, relation.id, user_id, "companion_request",
        message or "我需要你陪我几分钟", None,
    )
    partner_id = (
        relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
    )
    if partner_id:
        _notify_partner_async(partner_id, user_id, "companion_request", moment.content)
    return _moment_out(moment)
