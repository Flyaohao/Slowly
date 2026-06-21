from sqlalchemy import String, Text, BigInteger, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class RelationshipPractice(BigIntPKMixin, Base):
    __tablename__ = "relationship_practice"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    practice_type: Mapped[str] = mapped_column(String(30), nullable=False, unique=True)
    description: Mapped[str] = mapped_column(Text, nullable=False)


class PracticeRecord(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "practice_record"
    __table_args__ = (
        Index("ix_practice_record_practice_id", "practice_id"),
        Index("ix_practice_record_relation_id", "relation_id"),
        Index("ix_practice_record_initiator_id", "initiator_id"),
    )

    practice_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("relationship_practice.id"), nullable=False
    )
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    initiator_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    status: Mapped[str] = mapped_column(
        String(20), default="initiated", nullable=False
    )
    summary: Mapped[str] = mapped_column(Text, nullable=True)
