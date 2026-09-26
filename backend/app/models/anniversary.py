import datetime as dt
from sqlalchemy import String, Text, BigInteger, Boolean, Date, DateTime, ForeignKey, Index, func
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, Base


class Anniversary(BigIntPKMixin, Base):
    __tablename__ = "anniversary"
    __table_args__ = (
        Index("ix_anniversary_relation_id", "relation_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    anniversary_date: Mapped[dt.date] = mapped_column("date", Date, nullable=False)
    #: 整改 §8.8：true（默认）= 每年重复；false = 一次性，过了就不再算「下一次」。
    #: 用真实列而不是 JSON——首页「最近的一个纪念日」要靠它算，可查询。
    repeat_annually: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="1", nullable=False
    )
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_at: Mapped[dt.datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )


class Wishlist(BigIntPKMixin, Base):
    __tablename__ = "wishlist"
    __table_args__ = (
        Index("ix_wishlist_relation_id", "relation_id"),
        Index("ix_wishlist_status", "status"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(
        String(20), default="pending", nullable=False
    )
    completed_at: Mapped[Optional[dt.datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[dt.datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
