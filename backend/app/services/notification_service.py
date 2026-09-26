"""通知服务

两条自建通道（用户 2026-09-16 拍板，不接任何第三方推送 SDK）：
1. WebSocket 实时通知 —— App 进程存活时秒级送达，客户端转系统通知栏；
2. 邮件通知 —— 用户显式打开开关后生效，App 被杀 / 锁屏也能到达。

两者是补充关系，不是替代关系：WS 依赖长连接，邮件不依赖任何客户端状态。
"""

import asyncio
import logging
import threading
from datetime import datetime, timedelta
from typing import Optional, Dict, Any
from enum import Enum

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 事件循环投递：同步业务代码调用 async 通知的唯一入口
# ---------------------------------------------------------------------------

def _main_loop() -> Optional[asyncio.AbstractEventLoop]:
    """取服务主事件循环（首个 WebSocket connect 时记录在 ConnectionManager 上）。

    同步 `def` 端点跑在 FastAPI 线程池线程里——那里的 ``asyncio.get_event_loop()``
    在 Python 3.13 直接 RuntimeError，旧的 try/except 调用点因此**全部静默失联**。
    这里改成显式传递「连接时捕获的 loop」，不再依赖线程的隐式当前循环。
    """
    try:
        from app.services.mediation_service import manager
        return getattr(manager, "loop", None)
    except Exception:  # noqa: BLE001  循环探测失败绝不能影响业务主链路
        return None


def schedule_notify(coro, name: str = "notify") -> None:
    """把一个已构造好的通知协程安全投递出去（fire-and-forget）。

    决策顺序：
    1. 当前线程自带运行中的 loop（async 上下文）→ ``create_task``；
    2. 服务主 loop 在跑（普通 uvicorn 请求线程）→ ``run_coroutine_threadsafe``；
    3. 都没有（脚本 / 单测 / 尚无 WS 连接）→ ``asyncio.run`` 直接跑完——
       此时连接表必为空，协程只是空转，但**调用点可观测**（测试 spy 依赖这一点）。

    协程必须在调用点就构造好（作为实参传入），这样 monkeypatch 通知函数的
    测试在 schedule 的瞬间就能观测到调用。失败只记日志，绝不影响业务主链路。
    """
    try:
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is not None:
            running.create_task(coro)
            return

        loop = _main_loop()
        if loop is not None and loop.is_running() and not loop.is_closed():
            asyncio.run_coroutine_threadsafe(coro, loop)
            return

        asyncio.run(coro)
    except Exception as e:  # noqa: BLE001
        logger.warning("notify(%s) 调度失败：%s", name, e)
        try:
            coro.close()
        except Exception:  # noqa: BLE001
            pass


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

    # 在场感相关
    PARTNER_MOMENT = "partner_moment"
    COMPANION_REQUEST = "companion_request"

    # 通用
    SYSTEM_NOTICE = "system_notice"


# ---------------------------------------------------------------------------
# 邮件通道的限流与白名单
# ---------------------------------------------------------------------------

# 只有这三类事件值得把人从 App 外面叫回来：都是「对方做了一个需要你回应的动作」，
# 漏掉的代价远大于打扰的代价。其余事件（对方改了状态、读了信等）一律不发邮件。
EMAIL_NOTIFY_WHITELIST = frozenset({
    NotificationType.LETTER_RECEIVED.value,
    NotificationType.MEDIATION_INVITE.value,
    NotificationType.UNBIND_REQUESTED.value,
})

# 同类事件冷却：30 分钟内的第 2 封及以后不再发（避免对方连发几封信刷屏）
EMAIL_NOTIFY_COOLDOWN_MINUTES = 30

# 跨类型每日上限：无论什么事件，一个用户 24 小时内最多收这么多封
EMAIL_NOTIFY_DAILY_LIMIT = 10


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


def _deliver_email_notification(user_id: int, event_type: str) -> None:
    """后台线程里执行的完整发信链路：查开关 → 限流 → 发信 → 记台账。

    **不开请求级 db**：调用点遍布信件 / 解绑 / 调解三条业务链路，
    拿不到也不该拿它们的 Session（请求可能早已结束），所以自开自关。
    """
    from app.core.database import SessionLocal
    from app.repositories import notification_repo, user_repo
    from app.services import email_service

    if event_type not in EMAIL_NOTIFY_WHITELIST:
        return
    if not email_service.smtp_configured():
        return

    db = SessionLocal()
    try:
        user = user_repo.get_user_by_id(db, user_id)
        profile = user_repo.get_profile_by_user_id(db, user_id)
        if user is None or profile is None:
            return

        # 用户级开关，默认关；关着时行为与"没有这个功能"完全一致
        if not profile.email_notify_enabled:
            return

        now = datetime.utcnow()
        if notification_repo.count_logs(
            db, user_id,
            since=now - timedelta(minutes=EMAIL_NOTIFY_COOLDOWN_MINUTES),
            event_type=event_type,
        ) > 0:
            logger.info(
                "邮件通知命中冷却，跳过：user=%s event=%s", user_id, event_type
            )
            return

        if notification_repo.count_logs(
            db, user_id, since=now - timedelta(hours=24)
        ) >= EMAIL_NOTIFY_DAILY_LIMIT:
            logger.info(
                "邮件通知命中每日上限，跳过：user=%s event=%s", user_id, event_type
            )
            return

        sent = email_service.send_notification_mail(user.email, event_type)
        if sent:
            notification_repo.create_log(db, user_id, event_type)
    except Exception as e:
        logger.warning(
            "邮件通知发送失败：user=%s event=%s err=%s", user_id, event_type, e
        )
    finally:
        db.close()


def send_push_notification(user_id: int, event_type: str) -> None:
    """自建推送通道：邮件提醒（开关打开时才真正发出）。

    语义是 fire-and-forget —— 调用点都是「业务已经落库、顺手通知一下」的位置，
    不该为了一次 SMTP 往返（超时 15s）阻塞请求或事件循环，所以整段阻塞逻辑
    丢进后台守护线程，失败只记日志，绝不影响主流程。

    event_type 取 NotificationType 的取值，且必须在 EMAIL_NOTIFY_WHITELIST 内。
    邮件正文不携带任何业务内容，详见 email_service.NOTIFICATION_MAILS。
    """
    if event_type not in EMAIL_NOTIFY_WHITELIST:
        return

    threading.Thread(
        target=_deliver_email_notification,
        args=(user_id, event_type),
        name=f"email-notify-{user_id}-{event_type}",
        daemon=True,
    ).start()


async def notify_unbind_requested(requester_id: int, partner_id: int):
    """通知对方收到解绑请求"""
    await send_ws_notification(
        partner_id,
        NotificationType.UNBIND_REQUESTED,
        {"requester_id": requester_id}
    )
    send_push_notification(partner_id, NotificationType.UNBIND_REQUESTED.value)


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
    send_push_notification(receiver_id, NotificationType.LETTER_RECEIVED.value)


async def notify_mediation_invite(partner_id: int, session_id: int, inviter_name: str):
    """通知收到调解邀请"""
    await send_ws_notification(
        partner_id,
        NotificationType.MEDIATION_INVITE,
        {"session_id": session_id, "inviter_name": inviter_name}
    )
    send_push_notification(partner_id, NotificationType.MEDIATION_INVITE.value)


async def notify_partner_moment(partner_id: int, sender_id: int, moment_type: str, content: str):
    """实时通知对方：伴侣分享了此刻状态 / 发来陪伴请求。

    moment_type 由 presence_service 决定：text=此刻状态；companion_request=陪伴请求。
    客户端收到后刷新 presence 卡片；陪伴请求额外弹提示。

    这类事件**刻意不发邮件**：它天然高频，且不构成「需要你回应」的义务，
    发邮件只会把提醒变成噪音。
    """
    nt = (
        NotificationType.COMPANION_REQUEST
        if moment_type == "companion_request"
        else NotificationType.PARTNER_MOMENT
    )
    await send_ws_notification(
        partner_id,
        nt,
        {"sender_id": sender_id, "moment_type": moment_type, "content": content},
    )

