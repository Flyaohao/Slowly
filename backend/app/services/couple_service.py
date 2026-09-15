import asyncio
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from app.repositories import couple_repo, user_repo, invite_code_repo


async def _disconnect_user_websockets(user_id: int):
    """断开用户的 WebSocket 连接"""
    from app.services.mediation_service import manager
    await manager.disconnect_user(user_id, reason="Mode changed - unbind")


async def _notify_unbind_requested(requester_id: int, partner_id: int):
    """通知对方收到解绑请求"""
    from app.services.notification_service import notify_unbind_requested
    await notify_unbind_requested(requester_id, partner_id)


async def _notify_unbind_confirmed(user_a_id: int, user_b_id: int):
    """通知双方解绑已确认"""
    from app.services.notification_service import notify_unbind_confirmed
    await notify_unbind_confirmed(user_a_id, user_b_id)


def generate_invite_code(db: Session, user_id: int) -> dict:
    """生成恋爱码"""
    existing = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if existing:
        raise ValueError("30002")

    code = invite_code_repo.create_code(db, user_id)
    return {
        "invite_code": code.code,
        "expires_at": code.expires_at.isoformat(),
    }


def bind_couple(db: Session, user_id: int, invite_code_str: str) -> dict:
    """绑定情侣关系"""
    # 检查自己是否已有关系
    if couple_repo.get_relation_by_user_including_unbinding(db, user_id):
        raise ValueError("30002")

    # 验证恋爱码
    code = invite_code_repo.get_valid_code(db, invite_code_str)
    if not code:
        raise ValueError("30001")

    inviter_id = code.user_id
    if inviter_id == user_id:
        raise ValueError("30003")

    # 检查邀请者是否已有关系
    if couple_repo.get_relation_by_user_including_unbinding(db, inviter_id):
        raise ValueError("30002")

    # 创建关系
    relation = couple_repo.create_relation(db, inviter_id, user_id)

    # 标记恋爱码为已使用
    invite_code_repo.mark_used(db, code.id, user_id)

    # 更新双方 has_couple 标记
    user_repo.update_has_couple(db, inviter_id, True)
    user_repo.update_has_couple(db, user_id, True)

    return {
        "id": relation.id,
        "user_a_id": relation.user_a_id,
        "user_b_id": relation.user_b_id,
        "status": relation.status,
        "bind_time": relation.bind_time.isoformat() if relation.bind_time else None,
    }


def get_couple_info(db: Session, user_id: int) -> dict:
    """获取情侣信息"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation:
        raise ValueError("30005")

    space = couple_repo.get_space_by_relation_id(db, relation.id)

    return {
        "id": relation.id,
        "user_a_id": relation.user_a_id,
        "user_b_id": relation.user_b_id,
        "status": relation.status,
        "bind_time": relation.bind_time.isoformat() if relation.bind_time else None,
        "space": {
            "id": space.id,
            "name": space.name,
            "theme_color": space.theme_color,
            "background_url": space.background_url,
        } if space else None,
    }


def update_space(db: Session, user_id: int, data: dict) -> dict:
    """更新情侣空间"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation:
        raise ValueError("30005")

    couple_repo.update_space(db, relation.id, data)
    return get_couple_info(db, user_id)


def request_unbind(db: Session, user_id: int) -> None:
    """发起解绑"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation:
        raise ValueError("30005")

    partner_id = relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
    couple_repo.request_unbind(db, relation.id, user_id)

    # 通知对方
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.ensure_future(_notify_unbind_requested(user_id, partner_id))
        else:
            loop.run_until_complete(_notify_unbind_requested(user_id, partner_id))
    except Exception:
        pass


def confirm_unbind(db: Session, user_id: int) -> None:
    """确认解绑"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation or relation.status != "unbinding":
        raise ValueError("30005")

    # 不能自己确认自己发起的解绑
    if relation.unbind_requested_by == user_id:
        raise ValueError("30006")

    # 冷静期检查（72小时）
    if relation.unbind_requested_at:
        elapsed = datetime.utcnow() - relation.unbind_requested_at
        if elapsed < timedelta(hours=72):
            raise ValueError("30004")

    # 确认解绑
    user_a_id = relation.user_a_id
    user_b_id = relation.user_b_id
    couple_repo.confirm_unbind(db, relation.id)

    # 更新双方 has_couple 标记
    user_repo.update_has_couple(db, user_a_id, False)
    user_repo.update_has_couple(db, user_b_id, False)

    # 通知双方并断开 WebSocket
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.ensure_future(_notify_unbind_confirmed(user_a_id, user_b_id))
            asyncio.ensure_future(_disconnect_user_websockets(user_a_id))
            asyncio.ensure_future(_disconnect_user_websockets(user_b_id))
        else:
            loop.run_until_complete(_notify_unbind_confirmed(user_a_id, user_b_id))
            loop.run_until_complete(_disconnect_user_websockets(user_a_id))
            loop.run_until_complete(_disconnect_user_websockets(user_b_id))
    except Exception:
        pass


def cancel_unbind(db: Session, user_id: int) -> None:
    """取消解绑"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation or relation.status != "unbinding":
        raise ValueError("30005")

    couple_repo.cancel_unbind(db, relation.id)
