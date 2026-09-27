"""索引接线 worker（v3.2 §4.2 / 附录 §4.3：generation CAS，T5/T6）。

职责边界：**断言行的所有权先于向量写入**——

- `claim_index_work`：`pending_upsert` 乐观 `→indexing`（rowcount==1 才算
  认领，双 worker 竞争只有其一进入）；`pending_remove` 无中间态，按读到的
  generation 收尾 CAS 认领。到期的 `failed` 行只经 `task.next_retry_at`
  （join）推进 `→pending_upsert`（§2.4 gen+1），task `index_attempts` 封顶
  `MAX_INDEX_ATTEMPTS`。
- `_run_upsert`：预检（状态/关系/代际）→ **事务外** embed → chroma upsert →
  后复查 → CAS `WHERE status='active' AND index_generation=G
  AND index_status='indexing'`；rowcount==0 → **立即撤下刚写入的向量**、
  不标 `indexed`（§4.3：DB 是权威、向量是派生）。
- `_run_remove`：幂等 delete；generation 未变才标 `removed`。
- 任务终结：task 的行无 `pending_upsert/indexing/pending_remove` 剩余 →
  有 `failed`（且重试耗尽）→ `memory_available_index_failed`（MySQL 关键词
  通道照常可用），否则 `completed`。

Stage A 已知边界：`indexing` 行无租约（崩溃残留由断言离场联动或人工处理）；
无 `pipeline_task_id` 的双写行（非 chat 源）失败后不自动重试（留 `failed`
= 关键词兜底）。测试通过 patch `_collection` / `_embed` 注入。
"""
import logging
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

from sqlalchemy import update
from sqlalchemy.orm import Session

from app.models.ai import AiMemory, MemoryPipelineTask
from app.services.memory_pipeline import _relation_active
from app.services.memory_status_service import transition_index, transition_task

logger = logging.getLogger("couple.memory.index")

#: 单任务索引重试总上限（含首次），耗尽 → 行留 failed、任务
#: `memory_available_index_failed`
MAX_INDEX_ATTEMPTS = 5
#: 指数退避基数：10s、20s、40s……
INDEX_BACKOFF_SECONDS = 10

#: 任务在途索引状态（任一存在即任务保持 indexing）
_IN_FLIGHT = ("pending_upsert", "indexing", "pending_remove")


def _utcnow() -> datetime:
    """naive UTC（与 memory_pipeline 同口径）。"""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _collection():
    """向量集合（测试注入位：patch `memory_index_worker._collection`）。"""
    from app.services.memory_retrieval import _get_collection

    return _get_collection()


def _embed(text: str):
    """embedding（测试注入位：patch `memory_index_worker._embed`）。"""
    from app.services.embedding import embeddings

    return embeddings.embed_documents([text])[0]


def _metadata(row: AiMemory) -> Dict:
    """chroma metadata：与 legacy `vectorize_memory_async` 同键（读侧过滤依赖）。"""
    meta = {
        "memory_id": int(row.id),
        "user_id": int(row.user_id or 0),
        "relation_id": int(row.relation_id or 0),
        "visibility": row.visibility or "private",
        "memory_type": row.memory_type or "",
        "importance": int(row.importance or 0),
    }
    if row.source:
        meta["source"] = row.source
    if row.occurred_at:
        # Chroma metadata 不接受 datetime/None——iso 字符串与 legacy 同口径
        meta["occurred_at"] = row.occurred_at.isoformat()
    return meta


# ---------------------------------------------------------------------------
# 领取
# ---------------------------------------------------------------------------

def claim_index_work(db: Session, *, limit: int = 1) -> List[Dict]:
    """领取索引工作：先推进到期 failed 行，再领 pending_upsert/pending_remove。

    返回 work 列表 `{"kind", "id", "generation", "task_id"}`。
    """
    now = _utcnow()

    # 1) 到期 failed → pending_upsert（§2.4 gen+1；仅经 task.next_retry_at）
    retry_rows = (
        db.query(AiMemory.id, AiMemory.pipeline_task_id)
        .join(
            MemoryPipelineTask,
            MemoryPipelineTask.id == AiMemory.pipeline_task_id,
        )
        .filter(
            AiMemory.index_status == "failed",
            MemoryPipelineTask.state.in_(("memory_committed", "indexing")),
            MemoryPipelineTask.next_retry_at.isnot(None),
            MemoryPipelineTask.next_retry_at <= now,
            MemoryPipelineTask.index_attempts < MAX_INDEX_ATTEMPTS,
        )
        .limit(limit)
        .all()
    )
    for row_id, task_id in retry_rows:
        result = db.execute(
            update(AiMemory)
            .where(
                AiMemory.id == row_id,
                AiMemory.index_status == "failed",
            )
            .values(
                index_status="pending_upsert",
                index_generation=AiMemory.index_generation + 1,
            )
        )
        if result.rowcount == 1:
            logger.info(
                "[MEM-IDX] failed 退避到期重投 id=%s task=%s", row_id, task_id
            )
    db.commit()

    # 2) 领工作
    candidates = (
        db.query(
            AiMemory.id,
            AiMemory.index_status,
            AiMemory.index_generation,
            AiMemory.pipeline_task_id,
        )
        .filter(AiMemory.index_status.in_(("pending_upsert", "pending_remove")))
        .order_by(AiMemory.id.asc())
        .limit(limit)
        .all()
    )
    works: List[Dict] = []
    for row_id, status, generation, task_id in candidates:
        if status == "pending_upsert":
            # 乐观认领：generation 一并锁进 WHERE，防陈旧行被误领
            result = db.execute(
                update(AiMemory)
                .where(
                    AiMemory.id == row_id,
                    AiMemory.index_status == "pending_upsert",
                    AiMemory.index_generation == generation,
                )
                .values(index_status="indexing")
            )
            db.commit()
            if result.rowcount != 1:
                continue  # 双 worker 竞争，另一方已领
            works.append(
                {
                    "kind": "upsert",
                    "id": row_id,
                    "generation": generation,
                    "task_id": task_id,
                }
            )
        else:
            # pending_remove 无中间态（§2.4），靠收尾 CAS 保证单一落点
            works.append(
                {
                    "kind": "remove",
                    "id": row_id,
                    "generation": generation,
                    "task_id": task_id,
                }
            )
        if len(works) >= limit:
            break
    return works


# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------

def process_index_work(db: Session, work: Dict) -> None:
    """执行一条索引工作，收尾统一做任务终结判定。"""
    try:
        if work["kind"] == "upsert":
            _run_upsert(db, work)
        else:
            _run_remove(db, work)
    except Exception:
        logger.exception("[MEM-IDX] 索引工作异常 work=%s", work)
        try:
            db.rollback()
        except Exception:  # noqa: BLE001
            pass
    finally:
        _maybe_finish_task(db, work.get("task_id"))


def _run_upsert(db: Session, work: Dict) -> None:
    row = db.get(AiMemory, work["id"])
    if row is None:
        return  # 行已物理删除，无需处理
    if row.index_status != "indexing":
        # 认领后被断言侧联动改写（如离场 → pending_remove），交回状态机
        return
    gen = int(row.index_generation or 1)

    # ---- 预检（embed 之前）----
    if row.status != "active":
        transition_index(db, row, "pending_remove")
        db.commit()
        return
    if not _relation_active(db, row.relation_id):
        transition_index(db, row, "skipped")
        db.commit()
        return
    text = (row.memory_text or "").strip()
    if not text:
        transition_index(db, row, "skipped")
        db.commit()
        return

    # ---- 事务外 embed + upsert（长操作不持行锁）----
    try:
        vec = _embed(text)
        col = _collection()
        if col is None:
            raise RuntimeError("chroma collection unavailable")
        col.upsert(
            ids=[str(row.id)],
            embeddings=[vec],
            documents=[text],
            metadatas=[_metadata(row)],
        )
    except Exception as exc:  # noqa: BLE001——embed/chroma 失败统一走退避
        _on_index_failure(db, row, exc)
        return

    # ---- 后复查（结束旧事务，读到 embed 期间的最新状态）----
    db.commit()
    db.refresh(row)
    stale = (
        row.index_status != "indexing"
        or row.status != "active"
        or int(row.index_generation or 1) != gen
    )
    if stale:
        # embed 期间断言被改写/离场 → 撤下刚写入的向量，不标 indexed
        _safe_delete(str(row.id), col)
        return
    if not _relation_active(db, row.relation_id):
        _safe_delete(str(row.id), col)
        db.refresh(row)
        if row.index_status == "indexing":
            transition_index(db, row, "skipped")
            db.commit()
        return

    # ---- CAS：代际 + 状态 + active 三重门槛 ----
    result = db.execute(
        update(AiMemory)
        .where(
            AiMemory.id == row.id,
            AiMemory.index_status == "indexing",
            AiMemory.index_generation == gen,
            AiMemory.status == "active",
        )
        .values(
            index_status="indexed",
            indexed_generation=gen,
            indexed_at=_utcnow(),
            index_error=None,
        )
    )
    db.commit()
    if result.rowcount == 0:
        # CAS 落空 = 行在写入窗口被并发改写 → 立即删向量（§4.3 用例4）
        _safe_delete(str(row.id), col)
        logger.info("[MEM-IDX] CAS 落空，已撤下向量 id=%s gen=%s", row.id, gen)
        return
    logger.info("[MEM-IDX] 已索引 id=%s gen=%s", row.id, gen)


def _run_remove(db: Session, work: Dict) -> None:
    row = db.get(AiMemory, work["id"])
    if row is None:
        # 行已物理删除：向量孤儿尽力清理（legacy delete_memory 的兜底口径）
        _safe_delete(str(work["id"]), None)
        return
    if (
        row.index_status != "pending_remove"
        or int(row.index_generation or 1) != work["generation"]
    ):
        return  # 状态/代际已变，交回下一轮（不撤他人代际的向量）

    col = _collection()
    if col is not None:
        try:
            col.delete(ids=[str(row.id)])
        except Exception as exc:  # noqa: BLE001——留 pending_remove 下轮重试
            logger.warning("[MEM-IDX] 删除向量失败 id=%s: %s", row.id, exc)
            return
    # generation 未变才标 removed（CAS；不进 INDEX_GENERATION_BUMP）
    result = db.execute(
        update(AiMemory)
        .where(
            AiMemory.id == row.id,
            AiMemory.index_status == "pending_remove",
            AiMemory.index_generation == work["generation"],
        )
        .values(index_status="removed", index_error=None)
    )
    db.commit()
    if result.rowcount == 1:
        logger.info("[MEM-IDX] 已移除向量 id=%s gen=%s", row.id, work["generation"])


def _safe_delete(vector_id: str, col) -> None:
    """尽力删除向量（col 为 None 时按「无向量库」放行，与 legacy 删除口径同）。"""
    try:
        if col is None:
            col = _collection()
        if col is not None:
            col.delete(ids=[vector_id])
    except Exception as exc:  # noqa: BLE001
        logger.warning("[MEM-IDX] 撤下向量失败 id=%s: %s", vector_id, exc)


def _on_index_failure(db: Session, row: AiMemory, exc: Exception) -> None:
    """索引失败：行 → failed + 退避；task.index_attempts++（≤MAX 才可重试）。"""
    db.rollback()
    try:
        fresh = db.get(AiMemory, row.id)
        if fresh is None:
            return
        if fresh.index_status == "indexing":
            transition_index(db, fresh, "failed", error=str(exc)[:480])
        task_id = fresh.pipeline_task_id
        if task_id is not None:
            task = db.get(MemoryPipelineTask, task_id)
            if task is not None and task.state in ("memory_committed", "indexing"):
                task.index_attempts = int(task.index_attempts or 0) + 1
                if task.index_attempts < MAX_INDEX_ATTEMPTS:
                    backoff = INDEX_BACKOFF_SECONDS * (2 ** (task.index_attempts - 1))
                    task.next_retry_at = _utcnow() + timedelta(seconds=backoff)
                logger.info(
                    "[MEM-IDX] 索引失败退避 id=%s task=%s attempts=%s: %s",
                    fresh.id, task_id, task.index_attempts, exc,
                )
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("[MEM-IDX] 记录索引失败状态时异常 id=%s", row.id)


# ---------------------------------------------------------------------------
# 任务终结判定
# ---------------------------------------------------------------------------

def _maybe_finish_task(db: Session, task_id: Optional[int]) -> None:
    """task 终结规则（附录 §4.2）：无在途索引行 → completed /
    memory_available_index_failed；failed 仍有重试余地则保持 indexing 等待。
    """
    if not task_id:
        return
    try:
        task = db.get(MemoryPipelineTask, task_id)
        if task is None or task.state not in ("memory_committed", "indexing"):
            return
        statuses = {
            status
            for (status,) in db.query(AiMemory.index_status)
            .filter(AiMemory.pipeline_task_id == task_id)
            .all()
        }
        if statuses & set(_IN_FLIGHT):
            if task.state == "memory_committed":
                transition_task(db, task, "indexing")
                db.commit()
            return

        failed = "failed" in statuses
        attempts = int(task.index_attempts or 0)
        if failed and attempts < MAX_INDEX_ATTEMPTS:
            # 重试窗口未用尽 → 保持 indexing，等 next_retry_at 到期
            if task.state == "memory_committed":
                transition_task(db, task, "indexing")
            if task.next_retry_at is None:
                task.next_retry_at = _utcnow() + timedelta(
                    seconds=INDEX_BACKOFF_SECONDS * (2 ** max(attempts - 1, 0))
                )
            db.commit()
            return

        if failed:
            transition_task(
                db, task, "memory_available_index_failed",
                error_code="INDEX_EXHAUSTED",
                error_message="index rows failed after retries (keyword fallback on)",
            )
        else:
            transition_task(db, task, "completed")
        db.commit()
        logger.info(
            "[MEM-IDX] 任务终结 task=%s state=%s statuses=%s",
            task_id, task.state, sorted(statuses),
        )
    except Exception:
        db.rollback()
        logger.exception("[MEM-IDX] 任务终结判定异常 task=%s", task_id)


# ---------------------------------------------------------------------------
# 补偿式积压清扫（记忆系统升级 P0①：legacy 向量化失败行的重投出口）
#
# 背景：legacy 向量化（vectorize_memory_async）跑在 daemon 线程里，此前
# 「失败即丢、无补偿」，已造成真实数据缺陷（缺向量行）。失败行现在会标
# `index_status='pending_upsert'`（见 memory_retrieval._mark_index_pending），
# 本节负责把积压清掉。flags 全关（无 pipeline worker）时它也独立可用：
# claim_index_work 的 CAS 认领对无 pipeline_task 的 legacy 行同样生效，
# process_index_work 收尾的 _maybe_finish_task 对 task_id=None 是 no-op。
# ---------------------------------------------------------------------------

#: 单次清扫最多处理的行数（embed 是外呼，防一次性打爆预算）
SWEEP_MAX_ROWS = 50
#: 失败路径触发的清扫延迟（秒）：给瞬时故障一个恢复窗
SWEEP_DELAY_SECONDS = 30.0

_sweep_lock = threading.Lock()
_sweep_scheduled = False


def run_backlog_sweep(limit: int = SWEEP_MAX_ROWS) -> int:
    """清扫积压：把 pending_upsert 行逐条领出处理（含 legacy 无任务行）。

    claim_index_work 的乐观认领保证多线程/多进程并发清扫不重复处理；
    返回处理行数。轮次级异常上抛，由调用方记日志（清扫线程自吞）。
    """
    from app.core.database import SessionLocal

    processed = 0
    db = SessionLocal()
    try:
        while processed < limit:
            works = claim_index_work(db, limit=1)
            if not works:
                break
            for work in works:
                process_index_work(db, work)
                processed += 1
    finally:
        db.close()
    return processed


def schedule_backlog_sweep(delay: float = SWEEP_DELAY_SECONDS) -> None:
    """调度一次积压清扫（去抖：已有待执行的清扫则跳过）。

    两个触发点：
    - 服务启动（main.py startup，delay=5s）：扫上次进程退出/失败遗留的积压；
    - vectorize_memory_async 失败路径：延迟触发，给瞬时故障一个恢复窗。
    测试隔离：`COUPLE_DISABLE_MEMORY_DISTILL=1` 时不调度（与 pipeline
    worker 同款 kill switch），避免测试进程碰库/外呼 embedding。
    """
    global _sweep_scheduled
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return
    with _sweep_lock:
        if _sweep_scheduled:
            return
        _sweep_scheduled = True

    def _run() -> None:
        global _sweep_scheduled
        try:
            if delay > 0:
                time.sleep(delay)
            n = run_backlog_sweep()
            if n:
                logger.info("[MEM-IDX] 积压清扫完成，处理 %s 行", n)
        except Exception:  # noqa: BLE001——清扫线程绝不带崩进程
            logger.exception("[MEM-IDX] 积压清扫异常")
        finally:
            with _sweep_lock:
                _sweep_scheduled = False

    threading.Thread(target=_run, name="mem-idx-sweep", daemon=True).start()
