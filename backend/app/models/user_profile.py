from sqlalchemy import String, BigInteger, Boolean, Date, ForeignKey, SmallInteger
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
    # 出生时辰（0-23 小时，可空）：与 birthday 一起用于星盘简化推算，
    # 缺失时不编造上升星座（见 astrology_service.get_rising_sign）。
    birth_hour: Mapped[Optional[int]] = mapped_column(SmallInteger)
    # MBTI 16 型（INTJ 等），用户自选；空串/NULL 都表示未填。
    mbti: Mapped[Optional[str]] = mapped_column(String(8))
    # 出生地自由文本（"杭州"/"浙江省杭州市"），命中本地城市表才用于
    # 上升星座精算；匹配不到退回简化推算（birthplace_service）。
    birth_place: Mapped[Optional[str]] = mapped_column(String(50))
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
