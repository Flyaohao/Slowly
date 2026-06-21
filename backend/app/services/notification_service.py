"""通知服务

支持 WebSocket 实时通知和可扩展的推送通知。
"""

import logging
from typing import Optional, Dict, Any
from enum import Enum

logger = logging.getLogger(__name__)


class NotificationType(str, Enum):
    # 解绑相关
    UNBIND_REQUESTED = "unbind_requested"
    UNBIND_CONFIRMED = "unbind_confirmed"
    UNBIND_CANCELLED = "unbind_cancelled"

    # 信件相关
    LETTER_RECEIVED = "letter_received"
    LETTER_READ = "letter_read"

    # 调解相关
    MEDIATION_INVITE = "mediation_invite"
    MEDIATION_ACCEPTED = "mediation_accepted"
    MEDIATION_REJECTED = "mediation_rejected"
    MEDIATION_INPUT_DONE = "mediation_input_done"
    MEDIATION_CONFIRMED = "mediation_confirmed"

    # 通用
    SYSTEM_NOTICE = "system_notice"


async def send_ws_notification(user_id: int, notification_type: NotificationType, data: Dict[str, Any] = None):
    """通过 WebSocket 发送实时通知"""
    from app.services.mediation_service import manager

    message = {
        "type": "notification",
        "notification_type": notification_type.value,
        "data": data or {},
    }

    try:
        await manager.send_to_user(user_id, message)
        logger.info(f"WS notification sent to user {user_id}: {notification_type.value}")
    except Exception as e:
        logger.warning(f"Failed to send WS notification to user {user_id}: {e}")


def send_push_notification(user_id: int, title: str, body: str, data: Dict[str, Any] = None):
    """发送推送通知（预留接口，可接入 FCM/极光推送等）"""
    logger.info(f"Push notification to user {user_id}: {title} - {body}")
    # TODO: 接入实际的推送服务
    # fcm.send(user_id, title, body, data)


async def notify_unbind_requested(requester_id: int, partner_id: int):
    """通知对方收到解绑请求"""
    await send_ws_notification(
        partner_id,
        NotificationType.UNBIND_REQUESTED,
        {"requester_id": requester_id}
    )
    send_push_notification(
        partner_id,
        "收到解绑请求",
        "你的另一半发起了关系解绑请求",
        {"type": NotificationType.UNBIND_REQUESTED.value}
    )


async def notify_unbind_confirmed(user_a_id: int, user_b_id: int):
    """通知双方解绑已确认"""
    for uid in [user_a_id, user_b_id]:
        await send_ws_notification(
            uid,
            NotificationType.UNBIND_CONFIRMED,
            {"partner_id": user_b_id if uid == user_a_id else user_a_id}
        )


async def notify_unbind_cancelled(user_a_id: int, user_b_id: int):
    """通知双方解绑已取消"""
    for uid in [user_a_id, user_b_id]:
        await send_ws_notification(
            uid,
            NotificationType.UNBIND_CANCELLED,
            {}
        )


async def notify_letter_received(receiver_id: int, sender_id: int, letter_id: int, title: str):
    """通知收到新信件"""
    await send_ws_notification(
        receiver_id,
        NotificationType.LETTER_RECEIVED,
        {"sender_id": sender_id, "letter_id": letter_id, "title": title}
    )
    send_push_notification(
        receiver_id,
        "收到新信件",
        f"你收到了一封信：{title}",
        {"type": NotificationType.LETTER_RECEIVED.value, "letter_id": letter_id}
    )


async def notify_mediation_invite(partner_id: int, session_id: int, inviter_name: str):
    """通知收到调解邀请"""
    await send_ws_notification(
        partner_id,
        NotificationType.MEDIATION_INVITE,
        {"session_id": session_id, "inviter_name": inviter_name}
    )
    send_push_notification(
        partner_id,
        "调解邀请",
        f"{inviter_name} 邀请你进行冷静沟通",
        {"type": NotificationType.MEDIATION_INVITE.value, "session_id": session_id}
    )
