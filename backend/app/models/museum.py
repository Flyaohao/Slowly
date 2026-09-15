from sqlalchemy import String, Text, BigInteger, Boolean, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class MuseumItem(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "museum_item"
    __table_args__ = (
        Index("ix_museum_relation_id", "relation_id"),
        Index("ix_museum_item_type", "item_type"),
        Index("ix_museum_pinned", "pinned"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    item_type: Mapped[str] = mapped_column(String(30), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    story: Mapped[str] = mapped_column(Text, nullable=True)
    #: 藏品配图（照片类藏品），存相对路径如 /uploads/museum/xxx.jpg
    image_url: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    source_id: Mapped[int] = mapped_column(BigInteger, nullable=True)
    source_type: Mapped[str] = mapped_column(String(30), nullable=True)
    pinned: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
