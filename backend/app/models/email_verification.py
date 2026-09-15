from sqlalchemy import String, DateTime, Index
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class EmailVerificationCode(BigIntPKMixin, TimestampMixin, Base):
    """邮箱验证码（忘记密码等场景）。

    替代旧的进程内存 dict 存储：重启不丢、多进程/多实例可用、可审计。
    """

    __tablename__ = "email_verification_code"
    __table_args__ = (
        Index("ix_email_verification_code_email", "email"),
    )

    email: Mapped[str] = mapped_column(String(255), nullable=False)
    code: Mapped[str] = mapped_column(String(10), nullable=False)
    purpose: Mapped[str] = mapped_column(String(30), default="reset_password", nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    used_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
