from sqlalchemy import String, Text, BigInteger, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class PresenceMoment(BigIntPKMixin, TimestampMixin, Base):
    """在场感动态：此刻状态分享、陪伴请求都落这张表（moment_type 区分）。"""

    __tablename__ = "presence_moment"
    __table_args__ = (
        Index("ix_presence_moment_relation_id", "relation_id"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    #: text=此刻状态；companion_request=陪伴请求
    moment_type: Mapped[str] = mapped_column(String(30), default="text", nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    image_url: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
