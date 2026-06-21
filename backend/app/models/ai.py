from sqlalchemy import String, Text, Integer, BigInteger, ForeignKey, JSON
from sqlalchemy.orm import Mapped, mapped_column, relationship
from typing import Optional, List

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class AiScene(BigIntPKMixin, Base):
    __tablename__ = "ai_scene"

    scene_key: Mapped[str] = mapped_column(String(50), unique=True, nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(Text)


class AiPromptTemplate(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_prompt_template"

    scene_key: Mapped[str] = mapped_column(String(50), ForeignKey("ai_scene.scene_key"), nullable=False)
    template_content: Mapped[str] = mapped_column(Text, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)


class AiPromptVersion(BigIntPKMixin, Base):
    __tablename__ = "ai_prompt_version"

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    scene_key: Mapped[str] = mapped_column(String(50), nullable=False)
    prompt_content: Mapped[str] = mapped_column(Text, nullable=False)


class AiChatSession(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_chat_session"

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    scene_key: Mapped[str] = mapped_column(String(50), ForeignKey("ai_scene.scene_key"), nullable=False)
    title: Mapped[Optional[str]] = mapped_column(String(200))
    privacy_level: Mapped[str] = mapped_column(String(20), default="private", nullable=False)
    session_type: Mapped[str] = mapped_column(String(20), default="solo", nullable=False)
    partner_user_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=True
    )
    mediation_status: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)

    messages: Mapped[List["AiChatMessage"]] = relationship(back_populates="session")


class AiChatMessage(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_chat_message"

    session_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_chat_session.id"), nullable=False
    )
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    structured_output: Mapped[Optional[dict]] = mapped_column(JSON)
    risk_level: Mapped[Optional[str]] = mapped_column(String(30))
    token_count: Mapped[Optional[int]] = mapped_column(Integer)

    session: Mapped["AiChatSession"] = relationship(back_populates="messages")


class AiOutputFeedback(BigIntPKMixin, Base):
    __tablename__ = "ai_output_feedback"

    message_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_chat_message.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    rating: Mapped[Optional[int]] = mapped_column(Integer)
    feedback_tag: Mapped[Optional[str]] = mapped_column(String(50))
    feedback_text: Mapped[Optional[str]] = mapped_column(Text)


class AiKnowledgeDoc(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_knowledge_doc"

    title: Mapped[str] = mapped_column(String(200), nullable=False)
    source: Mapped[Optional[str]] = mapped_column(String(200))
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)

    chunks: Mapped[List["AiKnowledgeChunk"]] = relationship(back_populates="doc")


class AiKnowledgeChunk(BigIntPKMixin, Base):
    __tablename__ = "ai_knowledge_chunk"

    doc_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_knowledge_doc.id"), nullable=False
    )
    chunk_text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding_id: Mapped[Optional[str]] = mapped_column(String(100))
    chunk_metadata: Mapped[Optional[dict]] = mapped_column("metadata", JSON)

    doc: Mapped["AiKnowledgeDoc"] = relationship(back_populates="chunks")


class AiMemory(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_memory"

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    memory_type: Mapped[str] = mapped_column(String(30), nullable=False)
    memory_text: Mapped[str] = mapped_column(Text, nullable=False)
    visibility: Mapped[str] = mapped_column(String(20), default="private", nullable=False)
