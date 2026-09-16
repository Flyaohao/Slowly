from sqlalchemy import String, BigInteger, Boolean, Date, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from datetime import date
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class UserProfile(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "user_profile"

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), unique=True, nullable=False
    )
    nickname: Mapped[Optional[str]] = mapped_column(String(50))
    avatar_url: Mapped[Optional[str]] = mapped_column(String(500))
    gender: Mapped[Optional[str]] = mapped_column(String(10))
    birthday: Mapped[Optional[date]] = mapped_column(Date)
    city: Mapped[Optional[str]] = mapped_column(String(50))
    signature: Mapped[Optional[str]] = mapped_column(String(200))
    love_anniversary: Mapped[Optional[date]] = mapped_column(Date)
    private_password_hash: Mapped[Optional[str]] = mapped_column(String(255))

    # 邮件通知开关：默认关闭（用户拍板「设置里可选，不随时启用」）。
    # 关闭时通知行为与开关不存在时完全一致——只打日志，不发信。
    email_notify_enabled: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="profile")
