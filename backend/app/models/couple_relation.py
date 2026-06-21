from sqlalchemy import String, BigInteger, DateTime, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class CoupleRelation(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "couple_relation"
    __table_args__ = (
        Index("ix_couple_relation_user_a_id", "user_a_id"),
        Index("ix_couple_relation_user_b_id", "user_b_id"),
    )

    user_a_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    user_b_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)
    bind_time: Mapped[Optional[datetime]] = mapped_column(DateTime)
    unbind_requested_by: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("user.id")
    )
    unbind_requested_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    unbind_confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    space: Mapped["CoupleSpace"] = relationship(back_populates="relation", uselist=False)
