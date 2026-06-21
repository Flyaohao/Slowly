from sqlalchemy import String, Text, Integer, Float, Boolean, BigInteger, ForeignKey, JSON, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from typing import Optional, List

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class Questionnaire(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire"

    title: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(Text)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)

    questions: Mapped[List["QuestionnaireQuestion"]] = relationship(back_populates="questionnaire")


class QuestionnaireQuestion(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire_question"

    questionnaire_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire.id"), nullable=False
    )
    question_text: Mapped[str] = mapped_column(Text, nullable=False)
    question_type: Mapped[str] = mapped_column(String(20), nullable=False)
    dimension_key: Mapped[str] = mapped_column(String(50), nullable=False)
    is_required: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    weight: Mapped[float] = mapped_column(Float, default=1.0, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    questionnaire: Mapped["Questionnaire"] = relationship(back_populates="questions")
    options: Mapped[List["QuestionnaireOption"]] = relationship(back_populates="question")


class QuestionnaireOption(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire_option"

    question_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire_question.id"), nullable=False
    )
    option_text: Mapped[str] = mapped_column(String(500), nullable=False)
    score_value: Mapped[float] = mapped_column(Float, nullable=False)
    dimension_delta: Mapped[Optional[dict]] = mapped_column(JSON)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    question: Mapped["QuestionnaireQuestion"] = relationship(back_populates="options")


class QuestionnaireAnswer(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire_answer"
    __table_args__ = (
        UniqueConstraint("user_id", "questionnaire_id", "question_id", name="uq_user_questionnaire_question"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    questionnaire_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire.id"), nullable=False
    )
    question_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire_question.id"), nullable=False
    )
    answer_value: Mapped[dict] = mapped_column(JSON, nullable=False)


class QuestionnaireProgress(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire_progress"
    __table_args__ = (
        UniqueConstraint("user_id", "questionnaire_id", name="uq_user_questionnaire_progress"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    questionnaire_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire.id"), nullable=False
    )
    current_question_index: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class QuestionnaireSubmission(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "questionnaire_submission"

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    questionnaire_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("questionnaire.id"), nullable=False
    )
    questionnaire_title: Mapped[str] = mapped_column(String(200), nullable=False)
    total_questions: Mapped[int] = mapped_column(Integer, nullable=False)
    answered_count: Mapped[int] = mapped_column(Integer, nullable=False)
    profile_type: Mapped[Optional[str]] = mapped_column(String(50))
    profile_summary: Mapped[Optional[str]] = mapped_column(Text)
    dimension_scores: Mapped[Optional[dict]] = mapped_column(JSON)
    analysis_text: Mapped[Optional[str]] = mapped_column(Text)
    couple_profile_ready: Mapped[bool] = mapped_column(Boolean, default=False)
