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


def smtp_configured() -> bool:
    return bool(SMTP_USER and SMTP_AUTH_CODE)


def send_verification_code(to_email: str, code: str, minutes: int = 10) -> None:
    """发送验证码邮件。失败且非 dev 模式时抛 ValueError("20004")。"""
    subject = "情侣 AI 翻译官 · 密码重置验证码"
    body = (
        f"你好！\n\n"
        f"你正在重置密码，验证码为：{code}\n\n"
        f"验证码 {minutes} 分钟内有效。如果不是你本人操作，请忽略本邮件。\n\n"
        f"—— 情侣 AI 翻译官"
    )

    if EMAIL_DEV_MODE or not smtp_configured():
        logger.warning("[EMAIL_DEV_MODE] SMTP 未配置或 dev 模式开启，验证码未真实发送：email=%s code=%s", to_email, code)
        if not smtp_configured() and not EMAIL_DEV_MODE:
            raise ValueError("20004")
        return

    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = formataddr(("情侣 AI 翻译官", EMAIL_FROM or SMTP_USER))
    msg["To"] = formataddr(("", to_email))

    try:
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
        logger.info("验证码邮件已发送：email=%s", to_email)
    except Exception as e:
        logger.error("验证码邮件发送失败：email=%s err=%s", to_email, e)
        raise ValueError("20004")
