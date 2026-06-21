from sqlalchemy import String, Text, BigInteger, Boolean, DateTime, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class Letter(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "letter"
    __table_args__ = (
        Index("ix_letter_relation_id", "relation_id"),
        Index("ix_letter_sender_id", "sender_id"),
        Index("ix_letter_receiver_id", "receiver_id"),
        Index("ix_letter_status", "status"),
        Index("ix_letter_letter_type", "letter_type"),
    )

    relation_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=True
    )
    sender_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    receiver_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    letter_type: Mapped[str] = mapped_column(String(30), default="normal", nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="draft", nullable=False)
    send_time: Mapped[Optional[datetime]] = mapped_column(DateTime)
    unlock_time: Mapped[Optional[datetime]] = mapped_column(DateTime)
    is_private: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_favorite: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    deleted_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
