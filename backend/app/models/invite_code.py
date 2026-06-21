from sqlalchemy import String, BigInteger, Boolean, DateTime, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class InviteCode(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "invite_code"
    __table_args__ = (
        Index("ix_invite_code_user_id", "user_id"),
        Index("ix_invite_code_code", "code", unique=True),
    )

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    code: Mapped[str] = mapped_column(String(20), unique=True, nullable=False)
    is_used: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    used_by: Mapped[Optional[int]] = mapped_column(BigInteger, ForeignKey("user.id"))
    used_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
