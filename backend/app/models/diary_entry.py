from sqlalchemy import String, BigInteger, Boolean, Text, DateTime, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class DiaryEntry(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "diary_entry"
    __table_args__ = (
        Index("ix_diary_entry_user_id", "user_id"),
        Index("ix_diary_entry_deleted_at", "deleted_at"),
    )

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    mood: Mapped[Optional[str]] = mapped_column(String(50))
    weather: Mapped[Optional[str]] = mapped_column(String(50))
    is_favorite: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    deleted_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    #: P-C1 §1（v2_7）：关系 id——取到 active relation 才写日记记忆（单身日记不写）
    relation_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: 来源（v4.0 共同调解室）：NULL=普通观点；'mediation_room'=调解室结算压缩产生。
    #: D-OPINION：调解室观点默认不计入画像，来源列是用户筛选/展示的落点。
    source: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
