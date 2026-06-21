from sqlalchemy import String, Text, BigInteger, DateTime, ForeignKey, Index, func
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class DualPerspectiveEvent(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "dual_perspective_event"
    __table_args__ = (
        Index("ix_dpe_relation_id", "relation_id"),
        Index("ix_dpe_status", "status"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    event_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), default="one_side", nullable=False
    )


class DualPerspectiveRecord(BigIntPKMixin, Base):
    __tablename__ = "dual_perspective_record"
    __table_args__ = (
        Index("ix_dpr_event_id", "event_id"),
        Index("ix_dpr_user_id", "user_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    event_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("dual_perspective_event.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)
    visibility: Mapped[str] = mapped_column(
        String(20), default="hidden", nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
