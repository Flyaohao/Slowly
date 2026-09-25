"""蒸馏管线 T1–T4（v3.2 附录 §3；纯 DB 逻辑 + LLM 注入，线程壳在 worker）。

- T1 `enqueue_task`：与助手消息同一事务插 `distill_pending`；savepoint 隔离
  唯一键冲突，撞键返回已有任务（幂等）。
- T2 `claim_next_task`：乐观 UPDATE（附录 T2 逐字 + next_retry_at 退避门），
  `rowcount==1` 才获得任务；claim 即 commit——**LLM 调用不持事务/行锁**。
- `run_distill`：读上下文 → `invoke_structured(scene='memory_distill',
  max_tokens=600)`；`should_remember=False` → `completed_noop`；
  LLM 失败 → attempts++ + 指数退避，耗尽 → `failed`。
- T3 `save_extraction`：短事务复核 lease + relation 状态 → 存结构化候选
  （**不存消息原文**）→ `extraction_saved`；崩溃恢复直接读，不再调模型。
- T4 `commit_assertions`：单事务插入断言 + 证据 + 任务 `memory_committed`
  （worker 开则顺转 `indexing`）。两级幂等第二级：同任务同指纹 no-op。
  v3 字段推导失败：`REQUIRE_COMPLETE=1` → 回滚重试/failed（绝不写
  `legacy_pending`）；`=0` → 按 §6.3 落 `legacy_pending` 行（核心列照写）。
"""
import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

from sqlalchemy import or_, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core import config as app_config
from app.models.ai import (
    AiChatMessage,
    AiChatSession,
    AiMemory,
    MemoryPipelineTask,
)
from app.services.memory_evidence_service import attach_evidence, full_span
from app.services.memory_fingerprint import (
    PIPELINE_VERSION,
    item_fingerprint,
    task_idempotency_key,
)
from app.services.memory_identity import assign_identity, enforce_identity
from app.services.memory_ownership import default_ownership
from app.services.memory_registry import (
    PREDICATES,
    build_fact_key,
    cardinality_for,
    normalize_object_key,
)
from app.services.memory_status_service import transition_task

logger = logging.getLogger("couple.memory.pipeline")

#: T2 乐观 lease（附录 T2 逐字口径）
LEASE_SECONDS = 60
#: distill 总尝试上限（含首次），耗尽 → failed
MAX_DISTILL_ATTEMPTS = 3
#: 指数退避：10s、20s、40s……
DISTILL_BASE_BACKOFF_SECONDS = 10
#: 单轮领取任务上限（worker 每 tick）
CLAIM_BATCH = 5
#: pipeline 蒸馏输出 token 上限（legacy 路径保持 300 不动）
PIPELINE_DISTILL_MAX_TOKENS = 600
#: 与 memory_service._MIN_INPUT_LEN 同值（独立常量，避免跨模块私有引用）
MIN_INPUT_LEN = 6
#: extraction_result 的 prompt 版本标记（评测可追溯）
DISTILL_PROMPT_VERSION = "v3.2.0-distill"

#: 任务可领取状态（T2）
_CLAIMABLE_STATES = ("distill_pending", "extraction_saved")


class FieldsIncomplete(Exception):
    """断言核心字段不全（缺 memory_text / 证据源消息已删）——重试或 failed 信号。"""


def _utcnow() -> datetime:
    """naive UTC（lease/退避比较两侧都用本函数，与 MySQL UTC_TIMESTAMP 对齐）。"""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _release_lock(task: MemoryPipelineTask) -> None:
    task.locked_by = None
    task.locked_until = None


def _finish(db: Session, task: MemoryPipelineTask, new_state: str, **kw) -> None:
    """终态转换 + 释放 lease + 提交（任务级操作的统一出口）。"""
    transition_task(db, task, new_state, **kw)
    _release_lock(task)
    db.commit()


def _relation_active(db: Session, relation_id: int) -> bool:
    from app.models.couple_relation import CoupleRelation

    row = (
        db.query(CoupleRelation.status)
        .filter(CoupleRelation.id == relation_id)
        .first()
    )
    return bool(row) and row[0] == "active"


# ---------------------------------------------------------------------------
# T1：入队（幂等）
# ---------------------------------------------------------------------------

def enqueue_task(
    db: Session,
    *,
    relation_id: int,
    trigger_kind: str,
    source_type: str,
    source_id: int,
    source_revision_id: int = 1,
    requested_by_user_id: Optional[int] = None,
) -> MemoryPipelineTask:
    """T1：插 `distill_pending` 任务。唯一键冲突 → savepoint 回滚返回已有。

    **调用方在助手消息同一事务内调用**（附录 T1：消息 + 任务原子提交）；
    本函数自身不 commit。savepoint 保证撞键只丢任务行、不拖垮外层消息插入。
    """
    key = task_idempotency_key(
        trigger_kind, source_type, source_id, str(source_revision_id)
    )
    existing = (
        db.query(MemoryPipelineTask)
        .filter(MemoryPipelineTask.idempotency_key == key)
        .first()
    )
    if existing is not None:
        return existing

    task = MemoryPipelineTask(
        relation_id=relation_id,
        requested_by_user_id=requested_by_user_id,
        trigger_kind=trigger_kind,
        source_type=source_type,
        source_id=source_id,
        source_revision_id=source_revision_id,
        pipeline_version=PIPELINE_VERSION,
        idempotency_key=key,
        state="distill_pending",
    )
    try:
        with db.begin_nested():
            db.add(task)
            db.flush()
    except IntegrityError:
        # savepoint 已回滚，外层事务（调用方的助手消息）安然无恙；
        # 但失败的 pending 对象必须摘掉，否则后续 query 触发 autoflush
        # 会再次 INSERT 同一键、把同一个 IntegrityError 再抛一遍。
        try:
            db.expunge(task)
        except Exception:  # noqa: BLE001——已被 SQLAlchemy 摘除则忽略
            pass
        existing = (
            db.query(MemoryPipelineTask)
            .filter(MemoryPipelineTask.idempotency_key == key)
            .first()
        )
        if existing is None:
            raise
        logger.info("[MEM-PIPE] 任务已存在（幂等）key=%s…", key[:16])
        return existing
    return task


# ---------------------------------------------------------------------------
# T2：领取（乐观 lease）
# ---------------------------------------------------------------------------

def claim_next_task(
    db: Session, worker_id: str, *, lease_seconds: int = LEASE_SECONDS
) -> Optional[MemoryPipelineTask]:
    """T2：乐观领取一个到期任务；`rowcount==1` 才获得。**内部 commit**。

    WHERE 附录 T2 逐字（state IN + locked_until 到期），外加 `next_retry_at`
    退避门——失败重试的任务在退避窗口内不可被领。
    """
    now = _utcnow()
    candidates = (
        db.query(MemoryPipelineTask.id)
        .filter(
            MemoryPipelineTask.state.in_(_CLAIMABLE_STATES),
            or_(
                MemoryPipelineTask.locked_until.is_(None),
                MemoryPipelineTask.locked_until < now,
            ),
            or_(
                MemoryPipelineTask.next_retry_at.is_(None),
                MemoryPipelineTask.next_retry_at <= now,
            ),
        )
        .order_by(MemoryPipelineTask.id.asc())
        .limit(CLAIM_BATCH)
        .all()
    )
    for (task_id,) in candidates:
        locked_until = now + timedelta(seconds=lease_seconds)
        result = db.execute(
            update(MemoryPipelineTask)
            .where(
                MemoryPipelineTask.id == task_id,
                MemoryPipelineTask.state.in_(_CLAIMABLE_STATES),
                or_(
                    MemoryPipelineTask.locked_until.is_(None),
                    MemoryPipelineTask.locked_until < now,
                ),
                or_(
                    MemoryPipelineTask.next_retry_at.is_(None),
                    MemoryPipelineTask.next_retry_at <= now,
                ),
            )
            .values(
                locked_by=worker_id,
                locked_until=locked_until,
                distill_attempts=MemoryPipelineTask.distill_attempts + 1,
            )
        )
        db.commit()  # T2：立即释放行锁，LLM 调用不持事务
        if result.rowcount == 1:
            # populate_existing：expire_on_commit=False 下同 session 的旧实例
            # 属性是陈旧值（evaluate 同步可能因内存中的旧 lease 跳过它），
            # 领取方必须拿到 DB 真值（attempts/state/lease）
            return (
                db.query(MemoryPipelineTask)
                .filter(MemoryPipelineTask.id == task_id)
                .populate_existing()
                .first()
            )
    return None


# ---------------------------------------------------------------------------
# 上下文加载与蒸馏
# ---------------------------------------------------------------------------

def _load_chat_context(
    db: Session, task: MemoryPipelineTask
) -> Optional[Dict[str, Any]]:
    """chat_message 源：assistant 消息 → session.scene_key + 前一条 user 消息。"""
    msg = db.get(AiChatMessage, task.source_id)
    if msg is None:
        return None
    sess = (
        db.query(AiChatSession)
        .filter(AiChatSession.id == msg.session_id)
        .first()
    )
    if sess is None:
        return None
    prev_user = (
        db.query(AiChatMessage)
        .filter(
            AiChatMessage.session_id == sess.id,
            AiChatMessage.role == "user",
            AiChatMessage.id < msg.id,
        )
        .order_by(AiChatMessage.id.desc())
        .first()
    )
    return {
        "scene_key": sess.scene_key,
        "session": sess,
        "user_msg": prev_user,
        "assistant_msg": msg,
    }


def _record_distill_failure(
    db: Session, task: MemoryPipelineTask, exc: Exception
) -> None:
    """LLM 失败：attempts（claim 时已 +1）达上限 → failed；否则指数退避重排。"""
    attempts = int(task.distill_attempts or 0)
    if attempts >= MAX_DISTILL_ATTEMPTS:
        _finish(
            db, task, "failed",
            error_code="DISTILL_EXHAUSTED",
            error_message=str(exc)[:480],
        )
        logger.warning(
            "[MEM-PIPE] 蒸馏重试耗尽 task=%s attempts=%s: %s",
            task.id, attempts, exc,
        )
        return
    backoff = DISTILL_BASE_BACKOFF_SECONDS * (2 ** max(attempts - 1, 0))
    task.next_retry_at = _utcnow() + timedelta(seconds=backoff)
    _release_lock(task)
    db.commit()
    logger.info(
        "[MEM-PIPE] 蒸馏失败退避 task=%s attempts=%s next=%ss",
        task.id, attempts, backoff,
    )


def run_distill(
    db: Session,
    task: MemoryPipelineTask,
    *,
    llm_client: Any = None,
) -> None:
    """LLM 蒸馏一步（T2/T3 之间——**不持事务/行锁**）。

    出口：`extraction_saved`（T3 完成）/ `completed_noop` / `completed_skipped`
    / `failed` / 保持 `distill_pending`（退避待重试）。
    `llm_client` 为测试注入位（默认 `distill_llm`）。
    """
    if task.state != "distill_pending":
        return  # extraction_saved 走 commit_assertions（崩溃恢复不再调模型）
    if not _relation_active(db, task.relation_id):
        _finish(db, task, "completed_skipped")
        return

    ctx = _load_chat_context(db, task)
    if ctx is None or ctx["user_msg"] is None:
        _finish(
            db, task, "failed",
            error_code="CONTEXT_MISSING",
            error_message="chat context or preceding user message missing",
        )
        return
    user_text = (ctx["user_msg"].content or "").strip()
    if len(user_text) < MIN_INPUT_LEN:
        _finish(db, task, "completed_noop")
        return

    if llm_client is None:
        from app.services.memory_service import distill_llm

        if not distill_llm.api_key:
            _finish(
                db, task, "failed",
                error_code="LLM_NOT_CONFIGURED",
                error_message="AI_API_KEY missing",
            )
            return
        llm_client = distill_llm

    from app.services.memory_service import build_distill_user_message
    from app.services.prompt_builder import MEMORY_DISTILL_PROMPT

    try:
        result = llm_client.invoke_structured(
            [
                {"role": "system", "content": MEMORY_DISTILL_PROMPT},
                {
                    "role": "user",
                    "content": build_distill_user_message(
                        ctx["scene_key"], ctx["user_msg"].content,
                        ctx["assistant_msg"].content,
                    ),
                },
            ],
            scene="memory_distill",
            temperature=0.2,
            max_tokens=PIPELINE_DISTILL_MAX_TOKENS,
        )
    except Exception as exc:  # noqa: BLE001——LLM 层异常统一按失败退避
        _record_distill_failure(db, task, exc)
        return

    if not getattr(result, "should_remember", False):
        _finish(db, task, "completed_noop")
        return

    save_extraction(
        db, task, result,
        user_msg_id=ctx["user_msg"].id,
        scene_key=ctx["scene_key"],
    )


def save_extraction(
    db: Session,
    task: MemoryPipelineTask,
    result: Any,
    *,
    user_msg_id: int,
    scene_key: str,
) -> None:
    """T3：短事务持久化结构化候选 → `extraction_saved`。

    **不存消息原文**（附录 §1.5）：只留候选字段 + evidence_ref（id 引用）+
    模型与 Prompt 版本。崩溃恢复直接读本 JSON，不得重新蒸馏。
    """
    # 复核 lease 仍归本 worker（T3 第一步）
    now = _utcnow()
    fresh = (
        db.query(MemoryPipelineTask)
        .filter(
            MemoryPipelineTask.id == task.id,
            MemoryPipelineTask.locked_until.isnot(None),
            MemoryPipelineTask.locked_until >= now,
        )
        .first()
    )
    if fresh is None:
        logger.warning("[MEM-PIPE] lease 已失效，放弃保存 task=%s", task.id)
        db.rollback()
        return
    if not _relation_active(db, task.relation_id):
        _finish(db, task, "completed_skipped")
        return

    from app.core.config import AI_MEMORY_MODEL

    task.extraction_result = {
        "should_remember": True,
        "memory_type": getattr(result, "memory_type", None),
        "memory_text": getattr(result, "memory_text", ""),
        "predicate": getattr(result, "predicate", "other"),
        "object_hint": getattr(result, "object_hint", ""),
        "subject_role": getattr(result, "subject_role", "self"),
        "epistemic_hint": getattr(result, "epistemic_hint", "unknown"),
        # evidence_ref 只存 id 引用——原文仍在 ai_chat_message，不复制
        "evidence_ref": {
            "source_type": task.source_type,
            "source_id": user_msg_id,
            "source_revision_id": 1,
        },
        "scene_key": scene_key,
        "model": AI_MEMORY_MODEL,
        "prompt_version": DISTILL_PROMPT_VERSION,
    }
    task.next_retry_at = None
    _finish(db, task, "extraction_saved")


# ---------------------------------------------------------------------------
# T4：断言 + 证据 + 任务单事务
# ---------------------------------------------------------------------------

def _derive_v3_fields(
    db: Session,
    task: MemoryPipelineTask,
    extraction: Dict[str, Any],
    base: Dict[str, Any],
) -> Dict[str, Any]:
    """候选 → v3 契约列（身份/谓词/所有权）。推导失败抛任意异常。"""
    ref = extraction.get("evidence_ref") or {}
    user_msg = db.get(AiChatMessage, ref.get("source_id") or 0)
    if user_msg is None:
        raise FieldsIncomplete("evidence message missing")
    sess = (
        db.query(AiChatSession)
        .filter(AiChatSession.id == user_msg.session_id)
        .first()
    )
    if sess is None:
        raise FieldsIncomplete("chat session missing")

    # ---- 身份（§1 铁则：证据作者 = 发前置 user 消息的人，session 即作者）----
    author = sess.user_id
    partner = sess.partner_user_id
    subject_role = extraction.get("subject_role") or "self"
    subject_type = "user"
    subject_user_id: Optional[int] = author
    if subject_role == "partner":
        if partner is None:
            logger.warning(
                "[MEM-PIPE] subject_role=partner 但会话无伴侣，回退 self task=%s",
                task.id,
            )
        else:
            subject_user_id = partner
    elif subject_role == "relationship":
        subject_type, subject_user_id = "relationship", None
    elif subject_role == "event":
        subject_type, subject_user_id = "event", None
    claimed = subject_user_id if subject_role in ("self", "partner") else None
    identity = enforce_identity(
        assign_identity(
            evidence_author_user_id=author,
            claimed_speaker_user_id=claimed,
            subject_user_id=author,
            epistemic_hint=extraction.get("epistemic_hint"),
        ),
        subject_user_id=author,
    )

    # ---- 谓词注册表（§2：封闭 key，miss 不阻塞）----
    predicate = extraction.get("predicate") or "other"
    if predicate not in PREDICATES:
        predicate = "other"
    object_key, registry_miss = normalize_object_key(extraction.get("object_hint"))
    fact_key = build_fact_key(subject_type, subject_user_id, predicate, object_key)

    # ---- 所有权（§6）：chat 蒸馏沿 legacy 语义 source=None → 归 user ----
    owner, creator, ownership_type = default_ownership(None, user_id=sess.user_id)

    memory_type = extraction.get("memory_type")
    from app.services.memory_service import MEMORY_TYPES, _DEFAULT_MEMORY_TYPE

    if memory_type not in MEMORY_TYPES:
        memory_type = _DEFAULT_MEMORY_TYPE

    v3 = dict(base)
    v3.update(
        memory_type=memory_type,
        user_id=sess.user_id,
        schema_version="v3",
        reported_by_user_id=identity.reported_by_user_id,
        attributed_to_user_id=identity.attributed_to_user_id,
        epistemic_type=identity.epistemic_type,
        assertion_origin=identity.assertion_origin,
        subject_type=subject_type,
        subject_user_id=subject_user_id,
        predicate_code=predicate,
        object_key=object_key,
        fact_key=fact_key,
        cardinality=cardinality_for(predicate),
        registry_miss=bool(registry_miss),
        owner_user_id=owner,
        created_by_user_id=creator,
        ownership_type=ownership_type,
    )
    return v3


def _build_assertion(
    db: Session,
    task: MemoryPipelineTask,
    extraction: Dict[str, Any],
) -> Dict[str, Any]:
    """候选 → 断言字段集（核心列 + v3 列，或 legacy_pending 降级）。

    核心字段（memory_text / 证据源）缺失 → `FieldsIncomplete`（重试/failed）。
    v3 推导失败：`REQUIRE_COMPLETE=1` → 上抛 `FieldsIncomplete`（绝不写
    `legacy_pending`）；`=0` → 按 §6.3 核心列照写、标 `legacy_pending`。
    """
    memory_text = (extraction.get("memory_text") or "").strip()
    if not memory_text:
        raise FieldsIncomplete("memory_text empty")
    ref = extraction.get("evidence_ref") or {}
    msg = db.get(AiChatMessage, ref.get("source_id") or 0)
    if msg is None:
        raise FieldsIncomplete("evidence message missing")

    from app.services.memory_service import MEMORY_TYPES, _DEFAULT_MEMORY_TYPE

    memory_type = extraction.get("memory_type")
    if memory_type not in MEMORY_TYPES:
        memory_type = _DEFAULT_MEMORY_TYPE

    base: Dict[str, Any] = {
        # ---- legacy 列（与 distill_and_save → create_memory 默认逐字一致）----
        "user_id": task.requested_by_user_id or 0,  # v3 成功路径覆盖为 session.user_id
        "relation_id": task.relation_id,
        "memory_type": memory_type,
        "memory_text": memory_text,
        "visibility": "private",
        "source": None,
        "source_id": None,
        "importance": 0,
        "pipeline_task_id": task.id,
        "pipeline_version": PIPELINE_VERSION,
        "index_status": (
            "pending_upsert" if app_config.MEMORY_ASSERTION_INDEX_WORKER
            else "skipped"
        ),
    }

    try:
        fields = _derive_v3_fields(db, task, extraction, base)
    except FieldsIncomplete:
        raise  # 证据源缺失属于核心字段问题，两 flag 策略一致
    except Exception:
        if app_config.MEMORY_ASSERTION_REQUIRE_COMPLETE:
            raise FieldsIncomplete("v3 field derivation failed (strict)") from None
        # §6.3：推导失败仍落库，标 legacy_pending 进补偿队列（指标桩）
        logger.warning(
            "[MEM-V3-COMP] pipeline v3 列推导失败，标 legacy_pending task=%s",
            task.id, exc_info=True,
        )
        fields = dict(base, schema_version="legacy_pending")

    # 证据范围（D6：全跨度，归一后码点）
    span_offset, span_length, span_hash = full_span(msg.content)
    fields["_evidence_ref"] = ref
    fields["_span"] = (span_offset, span_length, span_hash)
    return fields


def commit_assertions(db: Session, task: MemoryPipelineTask) -> None:
    """T4：单事务插入断言 + 证据 + 任务 `memory_committed`。

    两级幂等第二级：`(pipeline_task_id, item_fingerprint)` 撞键 → 该候选
    no-op。任何证据异常整笔回滚（任务保持可重试）。
    """
    if task.state != "extraction_saved":
        return  # distill_pending 未到 T3；其余状态已终态/进行中
    if not _relation_active(db, task.relation_id):
        _finish(db, task, "completed_skipped")
        return
    extraction = task.extraction_result
    if not extraction:
        _finish(
            db, task, "failed",
            error_code="NO_EXTRACTION",
            error_message="extraction_result missing",
        )
        return
    if not extraction.get("should_remember"):
        _finish(db, task, "completed_noop")
        return

    try:
        fields = _build_assertion(db, task, extraction)
    except FieldsIncomplete as exc:
        _handle_incomplete(db, task, str(exc))
        return
    except Exception:
        logger.exception("[MEM-PIPE] 断言构造异常 task=%s", task.id)
        _handle_incomplete(db, task, "assertion build error")
        return

    ref = fields.pop("_evidence_ref")
    span_offset, span_length, span_hash = fields.pop("_span")

    # 断言级幂等：同任务同指纹 → 已提交过（崩溃重放）
    fingerprint = item_fingerprint(
        source_type=ref.get("source_type") or "chat_message",
        source_id=int(ref.get("source_id") or 0),
        source_revision_id=str(ref.get("source_revision_id") or 1),
        offset=span_offset,
        length=span_length,
        predicate=fields.get("predicate_code") or "other",
        object_key=fields.get("object_key") or "other",
        subject_type=fields.get("subject_type") or "user",
        subject_user_id=fields.get("subject_user_id"),
        memory_text=fields["memory_text"],
    )
    already = (
        db.query(AiMemory.id)
        .filter(
            AiMemory.pipeline_task_id == task.id,
            AiMemory.item_fingerprint == fingerprint,
        )
        .first()
    )
    if already is not None:
        logger.info(
            "[MEM-PIPE] 断言幂等命中（重放）task=%s fp=%s…",
            task.id, fingerprint[:12],
        )
        _finish(db, task, "completed_noop")
        return

    # 与 legacy 同口径的跨任务去重（distill_and_save 的 (user,relation,text)）
    from app.services.memory_service import _is_duplicate_memory

    if _is_duplicate_memory(
        db, fields["user_id"], fields["relation_id"], fields["memory_text"]
    ):
        _finish(db, task, "completed_noop")
        return

    memory = AiMemory(**fields, item_fingerprint=fingerprint)
    db.add(memory)
    db.flush()
    if memory.occurred_at is None:
        if memory.created_at is None:
            db.refresh(memory, ["created_at"])
        memory.occurred_at = memory.created_at

    # 证据（唯一键冲突 no-op；同事务）
    attach_evidence(
        db,
        assertion_id=memory.id,
        source_type=ref.get("source_type") or "chat_message",
        source_id=int(ref.get("source_id") or 0),
        source_revision_id=int(ref.get("source_revision_id") or 1),
        offset_codepoint=span_offset,
        length_codepoint=span_length,
        hash_hex=span_hash,
    )
    _finish_commit(db, task, inserted=True)


def _finish_commit(db: Session, task: MemoryPipelineTask, *, inserted: bool) -> None:
    """T4 出口：`memory_committed`（+ worker 开则顺转 `indexing`）。"""
    if not inserted:
        _finish(db, task, "completed_noop")
        return
    transition_task(db, task, "memory_committed")
    _release_lock(task)
    if app_config.MEMORY_ASSERTION_INDEX_WORKER:
        transition_task(db, task, "indexing")
    else:
        transition_task(db, task, "completed")
    db.commit()


def _handle_incomplete(db: Session, task: MemoryPipelineTask, reason: str) -> None:
    """核心字段不全：attempts 耗尽 → failed；否则退避重排（两 flag 同策略）。

    `legacy_pending` 只发生在 v3 **推导**失败且 `REQUIRE_COMPLETE=0` 时
    （由 `_build_assertion` 就地降级落库）；核心列缺失永远写不出行，
    只能重试/failed。
    """
    db.rollback()
    # rollback 后对象过期，重读
    fresh = (
        db.query(MemoryPipelineTask)
        .filter(MemoryPipelineTask.id == task.id)
        .first()
    )
    if fresh is None:
        return
    attempts = int(fresh.distill_attempts or 0)
    if attempts >= MAX_DISTILL_ATTEMPTS:
        _finish(
            db, fresh, "failed",
            error_code="FIELDS_INCOMPLETE",
            error_message=reason[:480],
        )
        logger.warning(
            "[MEM-PIPE] 字段不全重试耗尽 task=%s: %s", fresh.id, reason
        )
        return
    backoff = DISTILL_BASE_BACKOFF_SECONDS * (2 ** max(attempts - 1, 0))
    fresh.next_retry_at = _utcnow() + timedelta(seconds=backoff)
    _release_lock(fresh)
    db.commit()
    logger.info(
        "[MEM-PIPE] 字段不全退避重试 task=%s attempts=%s next=%ss (%s)",
        fresh.id, attempts, backoff, reason,
    )


def process_task(
    db: Session, task: MemoryPipelineTask, *, llm_client: Any = None
) -> None:
    """驱动一个已领取任务走完 distill → extraction → commit（worker 入口）。"""
    try:
        if task.state == "distill_pending":
            run_distill(db, task, llm_client=llm_client)
        if task.state == "extraction_saved":
            commit_assertions(db, task)
    except Exception:
        logger.exception(
            "[MEM-PIPE] 任务处理异常 task=%s state=%s", task.id, task.state
        )
        try:
            db.rollback()
        except Exception:  # noqa: BLE001
            pass
