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

    #: 这个版本**为什么产生**。questionnaire=重新做问卷；viewpoint_enrich=由观点
    #: 补充画像；restore=撤回到某个历史版本；manual=用户手动存版本。
    #: 画像的每一次变更都是一个新版本，origin 是「哪一类变更」的物理落点——
    #: 没有它，历史列表里 20 条版本会长得一模一样，撤回就变成盲选。
    origin: Mapped[str] = mapped_column(
        String(32), nullable=False, server_default="questionnaire"
    )
    #: 可读的版本说明（如「由观点《异地恋要不要每天视频》补充价值取向」）。
    origin_note: Mapped[Optional[str]] = mapped_column(String(200))
    #: 来源追溯：若是观点触发的丰富，记下是哪条观点（diary_entry.id）。
    #: 刻意**不建外键**——观点被删掉后这个版本仍然成立，只是来源不可再跳转。
    source_viewpoint_id: Mapped[Optional[int]] = mapped_column(BigInteger)

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
