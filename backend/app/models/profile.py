from sqlalchemy import String, Text, Float, BigInteger, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from typing import Optional, List

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class PsychologyModel(BigIntPKMixin, Base):
    __tablename__ = "psychology_model"

    name: Mapped[str] = mapped_column(String(100), nullable=False)
    source: Mapped[Optional[str]] = mapped_column(String(200))
    description: Mapped[Optional[str]] = mapped_column(Text)


class RelationshipProfile(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "relationship_profile"

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    questionnaire_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire.id"), nullable=False
    )
    profile_type: Mapped[str] = mapped_column(String(50), nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    summary: Mapped[Optional[str]] = mapped_column(Text)
    version: Mapped[int] = mapped_column(default=1, nullable=False)

    dimension_scores: Mapped[List["ProfileDimensionScore"]] = relationship(back_populates="profile")


class ProfileDimensionScore(BigIntPKMixin, Base):
    __tablename__ = "profile_dimension_score"

    profile_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("relationship_profile.id"), nullable=False
    )
    dimension_key: Mapped[str] = mapped_column(String(50), nullable=False)
    score: Mapped[float] = mapped_column(Float, nullable=False)
    explanation: Mapped[Optional[str]] = mapped_column(Text)

    profile: Mapped["RelationshipProfile"] = relationship(back_populates="dimension_scores")


class CoupleProfile(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "couple_profile"

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    user_a_profile_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("relationship_profile.id"), nullable=False
    )
    user_b_profile_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("relationship_profile.id"), nullable=False
    )
    conflict_pattern: Mapped[Optional[str]] = mapped_column(String(100))
    summary: Mapped[Optional[str]] = mapped_column(Text)
