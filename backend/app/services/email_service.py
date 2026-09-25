"""邮件发送服务（QQ SMTP / 通用 SMTP）。

配置来自环境变量（compose 注入或 .env）：
- SMTP_HOST      默认 smtp.qq.com
- SMTP_PORT      默认 465（SSL）
- SMTP_USER      发信邮箱，如 1106665698@qq.com
- SMTP_AUTH_CODE SMTP 授权码（QQ 邮箱在 设置-账户-POP3/SMTP 生成，不是 QQ 密码）
- EMAIL_DEV_MODE true 时不开 SMTP 也能走通流程：验证码写库 + 打日志，
                  并由 forgot-password 接口返回 dev_code，便于联调。

未配置 SMTP 且非 dev 模式时，发信抛 ValueError("20004")，由端点归一为业务错误。
"""

import logging
import smtplib
from email.header import Header
from email.mime.text import MIMEText
from email.utils import formataddr

from app.core.config import (
    SMTP_HOST,
    SMTP_PORT,
    SMTP_USER,
    SMTP_AUTH_CODE,
    EMAIL_FROM,
    EMAIL_DEV_MODE,
)

logger = logging.getLogger(__name__)

# 邮件通知文案表：事件类型 → (标题, 正文)
#
# 隐私红线：这里出现的每一个字都会离开 App 落到第三方邮箱服务器上，
# 因此正文**只说明"发生了什么"，绝不带信件标题、正文、对方昵称等任何内容**。
# 用户要看内容必须打开 App（受登录态与私密密码保护）。
NOTIFICATION_MAILS = {
    "letter_received": (
        "情侣 AI 军师 · 你收到一封新信件",
        "有人给你写了一封信。\n\n"
        "打开「情侣 AI 军师」App 即可查看与回复。\n\n"
        "为了你的隐私，这封提醒不包含信件内容，信件只在 App 内可见。\n",
    ),
    "mediation_invite": (
        "情侣 AI 军师 · 你收到一次冷静沟通邀请",
        "对方邀请你进行一次冷静沟通。\n\n"
        "打开「情侣 AI 军师」App 查看并回应。\n\n"
        "为了你的隐私，这封提醒不包含沟通内容。\n",
    ),
    "unbind_requested": (
        "情侣 AI 军师 · 你收到一条关系解绑请求",
        "有人发起了关系解绑请求。\n\n"
        "打开「情侣 AI 军师」App 查看详情。如果不是你的意愿，"
        "冷静期内可以随时取消。\n",
    ),
}


def smtp_configured() -> bool:
    return bool(SMTP_USER and SMTP_AUTH_CODE)


def _send(to_email: str, subject: str, body: str) -> None:
    """真正走一次 SMTP。失败抛 ValueError("20004")。"""
    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = formataddr(("情侣 AI 军师", EMAIL_FROM or SMTP_USER))
    msg["To"] = formataddr(("", to_email))

    if SMTP_PORT == 465:
        server = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15)
    else:
        server = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15)
        server.starttls()
    try:
        server.login(SMTP_USER, SMTP_AUTH_CODE)
        server.sendmail(SMTP_USER, [to_email], msg.as_string())
    finally:
        server.quit()


def send_verification_code(to_email: str, code: str, minutes: int = 10) -> None:
    """发送验证码邮件。失败且非 dev 模式时抛 ValueError("20004")。"""
    subject = "情侣 AI 军师 · 密码重置验证码"
    body = (
        f"你好！\n\n"
        f"你正在重置密码，验证码为：{code}\n\n"
        f"验证码 {minutes} 分钟内有效。如果不是你本人操作，请忽略本邮件。\n\n"
        f"—— 情侣 AI 军师"
    )

    if EMAIL_DEV_MODE or not smtp_configured():
        logger.warning("[EMAIL_DEV_MODE] SMTP 未配置或 dev 模式开启，验证码未真实发送：email=%s code=%s", to_email, code)
        if not smtp_configured() and not EMAIL_DEV_MODE:
            raise ValueError("20004")
        return

    try:
        _send(to_email, subject, body)
        logger.info("验证码邮件已发送：email=%s", to_email)
    except Exception as e:
        logger.error("验证码邮件发送失败：email=%s err=%s", to_email, e)
        raise ValueError("20004")


def send_notification_mail(to_email: str, event_type: str) -> bool:
    """发送「通知类」邮件（有人给你写信 / 邀请调解 / 请求解绑）。

    与验证码邮件的区别在失败语义：验证码发不出去是硬失败（用户拿不到码），
    必须抛错让端点回业务错误；通知发不出去只是少了一条提醒，
    主流程早已完成，所以这里只返回 bool，由调用方记账。

    dev 模式或 SMTP 未配置时什么都不做，返回 False（不抛错）。
    """
    mail = NOTIFICATION_MAILS.get(event_type)
    if mail is None:
        logger.warning("未知的邮件通知事件类型：%s", event_type)
        return False

    if EMAIL_DEV_MODE or not smtp_configured():
        logger.info(
            "[EMAIL_DEV_MODE] 通知邮件未真实发送：email=%s event=%s", to_email, event_type
        )
        return False

    subject, body = mail
    try:
        _send(to_email, subject, body)
        logger.info("通知邮件已发送：email=%s event=%s", to_email, event_type)
        return True
    except Exception as e:
        logger.error(
            "通知邮件发送失败：email=%s event=%s err=%s", to_email, event_type, e
        )
        return False
