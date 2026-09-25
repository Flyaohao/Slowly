from datetime import datetime

from sqlalchemy import (
    Boolean,
    String,
    Text,
    Integer,
    BigInteger,
    DateTime,
    ForeignKey,
    Index,
    JSON,
    Numeric,
    SmallInteger,
    UniqueConstraint,
    PrimaryKeyConstraint,
    func,
)
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
    #: P-B §1.5：这条消息由哪一档生成（quick/deep/expert）。NULL=旧数据/未标注。
    #: 只落库不进 MessageOut——DTO 契约测试是静态正则扫描，等 P-C 一起放开。
    chat_mode: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)

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
        # P-C1 §1：时间衰减排序（occurred_at 为事件时间，NULL 回退 created_at）
        Index("ix_ai_memory_relation_occurred", "relation_id", "occurred_at"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    memory_type: Mapped[str] = mapped_column(String(30), nullable=False)
    memory_text: Mapped[str] = mapped_column(Text, nullable=False)
    visibility: Mapped[str] = mapped_column(String(20), default="private", nullable=False)
    # ---- P-C1 §1：事件记忆四列（v2_7）----
    #: 事件发生时间（非入库时间）；旧数据回填 = created_at
    occurred_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    #: 来源（letter/diary/dual/anniversary/questionnaire/museum/chat_summary/…）
    source: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    #: 源实体 id（如 diary_entry.id / letter.id）
    source_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: 0 常规 / 1 高价值（dual、anniversary、questionnaire）/ 2 用户标星（P-C3 枚举位）
    #: v3.2 起回归**系统重要度**：用户标星迁往 memory_assertion_user_state.starred
    importance: Mapped[int] = mapped_column(
        SmallInteger, default=0, server_default="0", nullable=False
    )

    # ------------------------------------------------------------------ #
    # 军师 AI 记忆系统 v3.2（契约附录 §1.1：ai_memory 原位升级为断言表）
    # 内容列（memory_text/认识论/主体/证据关系）append-only，插入后不可原地改；
    # 生命周期/索引状态/用户级状态按状态机更新（附录 §0.3）。
    # ------------------------------------------------------------------ #
    #: NULL=legacy 行（迁移期）；'v3'=新契约字段完整；'legacy_pending'=双写推导失败进补偿
    schema_version: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    #: 断言生命周期（附录 §2.2）：active/superseded/expired/archived/purging/purged
    status: Mapped[str] = mapped_column(
        String(20), default="active", server_default="active", nullable=False
    )
    #: superseded/invalidated/user_archived/revoke_share/relation_purge/expired/…
    status_reason: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    valid_from: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    valid_until: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)

    # ---- 身份（v3.2 §1：证据作者与被转述者分离）----
    #: 证据的实际作者（谁发出的源消息），可空；≠ attributed 时强制 attributed_report
    reported_by_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: 被陈述为原始说话人，可空；展示层 self/partner 由它推导
    attributed_to_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: user/relationship/event；subject_type='user' 时 subject_user_id 必填
    subject_type: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    subject_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: user_message/business_event/user_correction/inference/legacy
    assertion_origin: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    #: system_event/self_report/attributed_report/observation/interpretation/summary/unknown
    epistemic_type: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)

    # ---- 谓词注册表（v3.2 §2：封闭 enum，LLM 不得自由生成 key）----
    predicate_code: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)
    object_key: Mapped[Optional[str]] = mapped_column(String(120), nullable=True)
    #: 只管归组与冲突（§2.1 `subject / predicate / object_key`），永不做任务幂等
    fact_key: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    cardinality: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)
    #: 词典未命中 → object_key='other' + 本标记为真（进评测观察，不阻塞写入）
    registry_miss: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )

    # ---- 所有权（v3.2 §6.2：revoke_share 只认 ownership_type+created_by）----
    owner_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    created_by_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    ownership_type: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    #: 用户确认（是标志不是认识论型别——epistemic 不因确认升格）
    user_confirmed: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )
    #: 0.000..1.000；legacy 回填封顶 0.5（§8 三不）
    confidence: Mapped[Optional[float]] = mapped_column(Numeric(4, 3), nullable=True)
    #: 源被删：高风险断言**停止注入**（非降权），普通断言降权并标「证据缺失」
    evidence_lost: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )

    # ---- 管线（v3.2 §4：两级幂等——任务级唯一键 + 断言级 task_id+fingerprint）----
    #: FK 指向 memory_pipeline_task（附录 §1.5：先建任务表再加本 FK，顺序不得反转）
    pipeline_task_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("memory_pipeline_task.id"), nullable=True
    )
    #: SHA256(证据范围+normalized predicate+object_key+subject+normalized text)
    item_fingerprint: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    extractor_model: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    extractor_prompt_version: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)
    pipeline_version: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)

    # ---- 派生索引状态（附录 §1.1/§2.4：默认 'skipped'，防 legacy 行被误投递）----
    #: pending_upsert/indexing/indexed/pending_remove/removed/skipped/failed
    index_status: Mapped[str] = mapped_column(
        String(24), default="skipped", server_default="skipped", nullable=False
    )
    #: indexed_generation == index_generation 才表示 Chroma 内容有效
    index_generation: Mapped[int] = mapped_column(
        Integer, default=1, server_default="1", nullable=False
    )
    indexed_generation: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    embedding_model: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    embedding_version: Mapped[Optional[str]] = mapped_column(String(40), nullable=True)
    indexed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    index_error: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)


class MemoryPipelineTask(BigIntPKMixin, TimestampMixin, Base):
    """蒸馏/索引管线任务（附录 §1.5；v3.2 §4.2 任务态与断言索引态分离）。"""

    __tablename__ = "memory_pipeline_task"
    __table_args__ = (
        UniqueConstraint("idempotency_key", name="uk_memory_pipeline_idempotency"),
        Index("ix_memory_pipeline_claim", "state", "next_retry_at", "locked_until"),
        Index(
            "ix_memory_pipeline_source",
            "source_type", "source_id", "source_revision_id",
        ),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    requested_by_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    trigger_kind: Mapped[str] = mapped_column(String(30), nullable=False)
    source_type: Mapped[str] = mapped_column(String(30), nullable=False)
    source_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    #: 可编辑源（信件）编辑=新修订 → 新任务；聊天消息恒 1
    source_revision_id: Mapped[int] = mapped_column(
        Integer, default=1, server_default="1", nullable=False
    )
    pipeline_version: Mapped[str] = mapped_column(String(40), nullable=False)
    #: SHA256(pipeline_version\n trigger_kind\n source_type\n source_id\n source_revision_id)
    idempotency_key: Mapped[str] = mapped_column(String(64), nullable=False)
    #: distill_pending/extraction_saved/memory_committed/indexing/completed/
    #: completed_noop/completed_skipped/failed/memory_available_index_failed
    state: Mapped[str] = mapped_column(String(40), nullable=False)
    #: 结构化候选+evidence_ref+模型/Prompt 版本；**不存消息原文**（附录 §1.5）
    extraction_result: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    distill_attempts: Mapped[int] = mapped_column(
        SmallInteger, default=0, server_default="0", nullable=False
    )
    index_attempts: Mapped[int] = mapped_column(
        SmallInteger, default=0, server_default="0", nullable=False
    )
    next_retry_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    locked_by: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    locked_until: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    last_error_code: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)
    last_error_message: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    #: 候选正文保留期（到期清 payload，只留 hash/结果 id/版本信息）
    payload_purge_after: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)


class MemoryAssertionEdge(BigIntPKMixin, Base):
    """断言↔断言边（附录 §1.2；supports 已移出边表，由 evidence 承载）。"""

    __tablename__ = "memory_assertion_edge"
    __table_args__ = (
        UniqueConstraint(
            "parent_assertion_id", "child_assertion_id", "relation_type",
            name="uk_memory_edge",
        ),
        Index("ix_memory_edge_child", "child_assertion_id", "relation_type"),
        Index("ix_memory_edge_relation", "relation_id", "relation_type"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    #: supersedes：被取代的旧断言在 parent；invalidates：被纠错的旧断言在 parent
    parent_assertion_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_memory.id"), nullable=False
    )
    child_assertion_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_memory.id"), nullable=False
    )
    #: supersedes/merges/invalidates/contradicts（contradicts 固定 min→max）
    relation_type: Mapped[str] = mapped_column(String(20), nullable=False)
    created_by_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )


class MemoryAssertionEvidence(BigIntPKMixin, Base):
    """断言↔源证据（附录 §1.3；独立证据数唯一来源）。"""

    __tablename__ = "memory_assertion_evidence"
    __table_args__ = (
        UniqueConstraint(
            "assertion_id", "source_type", "source_id", "source_revision_id",
            "offset_codepoint", "length_codepoint", "evidence_role",
            name="uk_memory_evidence",
        ),
        Index(
            "ix_memory_evidence_source",
            "source_type", "source_id", "source_revision_id",
        ),
    )

    assertion_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_memory.id"), nullable=False
    )
    source_type: Mapped[str] = mapped_column(String(30), nullable=False)
    source_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    #: 不可变修订引用；不可编辑源（聊天消息）恒 1（§7.2）
    source_revision_id: Mapped[int] = mapped_column(Integer, nullable=False)
    #: Unicode 码点口径；计算前先归一化换行 \r\n→\n（§7.2，口径即字段注释）
    offset_codepoint: Mapped[int] = mapped_column(Integer, nullable=False)
    length_codepoint: Mapped[int] = mapped_column(Integer, nullable=False)
    #: 归一化后内容的 SHA-256；展示「为什么记住」时回读校验
    content_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    evidence_role: Mapped[str] = mapped_column(
        String(20), default="support", server_default="support", nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )


class MemoryAssertionUserState(TimestampMixin, Base):
    """按用户的个性化状态（附录 §1.4；缺行 ≡ 全 false）。

    与全局 status 职责互斥：一方 muted/hidden/starred 只影响自己，不动向量。
    """

    __tablename__ = "memory_assertion_user_state"
    __table_args__ = (
        PrimaryKeyConstraint("assertion_id", "user_id"),
        Index("ix_memory_user_state_user", "user_id", "hidden", "muted"),
    )

    assertion_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_memory.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    hidden: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )
    muted: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )
    starred: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )
