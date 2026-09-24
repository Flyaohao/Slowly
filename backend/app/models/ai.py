from datetime import datetime

from sqlalchemy import String, Text, Integer, BigInteger, DateTime, ForeignKey, Index, JSON
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
    # 会话列表与 GET /sessions/active 共用：scope 定位 + 按 last_message_at 排序
    __table_args__ = (
        Index(
            "ix_ai_session_scope",
            "user_id", "relation_id", "scene_key", "status", "last_message_at",
        ),
    )

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
    #: 调解专用状态，与会话生命周期 status 正交，勿混用
    mediation_status: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)

    # ---- P0-10A 会话边界 ----
    #: active / archived（与 mediation_status 无关）
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)
    last_message_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    message_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    #: 按字符数近似累计（不用 tiktoken，红线禁新依赖）
    token_total: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    #: 分段原因：timeout / scene_switch / manual / budget / archived
    segment_reason: Mapped[Optional[str]] = mapped_column(String(30))

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
    # doc_id 上的索引由外键约束隐式要求，MySQL 不允许在保留外键的情况下删除它。
    # 这里显式声明，使模型与真实库结构一致，避免再次被 autogenerate 判定为"多余索引"。
    __table_args__ = (Index("ix_ai_knowledge_chunk_doc_id", "doc_id"),)

    doc_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_knowledge_doc.id"), nullable=False
    )
    chunk_text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding_id: Mapped[Optional[str]] = mapped_column(String(100))
    chunk_metadata: Mapped[Optional[dict]] = mapped_column("metadata", JSON)

    doc: Mapped["AiKnowledgeDoc"] = relationship(back_populates="chunks")


class AiMemory(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_memory"
    # 同上：user_id / relation_id 的索引为外键所必需，显式声明以对齐模型与库结构。
    __table_args__ = (
        Index("ix_ai_memory_user_id", "user_id"),
        Index("ix_ai_memory_relation_id", "relation_id"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    memory_type: Mapped[str] = mapped_column(String(30), nullable=False)
    memory_text: Mapped[str] = mapped_column(Text, nullable=False)
    visibility: Mapped[str] = mapped_column(String(20), default="private", nullable=False)
