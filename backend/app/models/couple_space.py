from sqlalchemy import String, BigInteger, Boolean, Date, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from datetime import date
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class CoupleSpace(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "couple_space"

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), unique=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), default="我们的空间", nullable=False)
    theme_color: Mapped[str] = mapped_column(String(20), default="#BE185D", nullable=False)
    background_url: Mapped[Optional[str]] = mapped_column(String(500))
    next_meet_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    presence_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    relation: Mapped["CoupleRelation"] = relationship(back_populates="space")
