from sqlalchemy import String, Text, BigInteger, Boolean, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class SelfPractice(BigIntPKMixin, Base):
    """自我练习定义（单身模式专属）"""
    __tablename__ = "self_practice"

    title: Mapped[str] = mapped_column(String(200), nullable=False)
    practice_type: Mapped[str] = mapped_column(String(30), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    guidance: Mapped[str] = mapped_column(Text, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)


class SelfPracticeRecord(BigIntPKMixin, TimestampMixin, Base):
    """自我练习记录"""
    __tablename__ = "self_practice_record"
    __table_args__ = (
        Index("ix_self_practice_record_user_id", "user_id"),
        Index("ix_self_practice_record_practice_id", "practice_id"),
    )

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    practice_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("self_practice.id"), nullable=False
    )
    status: Mapped[str] = mapped_column(
        String(20), default="started", nullable=False
    )
    content: Mapped[Optional[str]] = mapped_column(Text)
    reflection: Mapped[Optional[str]] = mapped_column(Text)
