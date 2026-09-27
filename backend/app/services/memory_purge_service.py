"""解绑关系记忆清理（记忆系统升级 P0⑥）。

背景：`memory_purge_after / purged_at / legal_hold` 三列早已随迁移落地，
但全仓库没有任何执行逻辑——「解绑清理零实现」（评估报告复核 #7）。

链路：
    confirm_unbind（dissolve）→ 写 `memory_purge_after = now + 保留期`
    → 本模块清理线程周期扫描「到期 + dissolved + 无 legal_hold + 未留痕」
    的关系 → DB（断言行 + 边/证据/用户级状态）与 Chroma 向量同步清除
    → `memory_purged_at` 留痕。

纪律：
- DB 是权威、向量是派生：先 DB 事务删除 + 留痕 commit，再尽力删向量；
  向量删除失败只记日志（dissolved 关系已被 AI_RECALL 硬边界挡在召回外，
  残留向量不可达，下轮启动清扫/人工兜底）。
- legal_hold=True 只暂停物理清理，不恢复展示或召回（附录 §1.6 原语义）。
- 依赖行先删：memory_assertion_edge / evidence / user_state 对 ai_memory
  有外键，不先删会把 purge 变成一次约束报错。
"""
import logging
import os
import threading
import time
from datetime import datetime
from typing import List, Optional

from sqlalchemy.orm import Session

from app.core.config import MEMORY_PURGE_RETENTION_DAYS
from app.models.ai import (
    AiMemory,
    MemoryAssertionEdge,
    MemoryAssertionEvidence,
    MemoryAssertionUserState,
)
from app.models.couple_relation import CoupleRelation

logger = logging.getLogger("couple.memory.purge")

#: 清理线程轮询间隔（秒）：每小时扫一次足够——保留期以「天」计
PURGE_SWEEP_INTERVAL_SECONDS = 3600.0
#: 启动后首次清扫延迟（秒）：避开进程启动高峰
PURGE_START_DELAY_SECONDS = 60.0
#: in_ 删除分块大小
_CHUNK = 500

_purge_thread_lock = threading.Lock()
_purge_thread: Optional[threading.Thread] = None


def _utcnow() -> datetime:
    """naive UTC（与 memory_index_worker 同口径）。"""
    return datetime.utcnow()


def _chunks(ids: List[int], size: int = _CHUNK):
    for i in range(0, len(ids), size):
        yield ids[i : i + size]


def purge_relation_memories(db: Session, relation: CoupleRelation) -> int:
    """清除一个关系的全部军师记忆（DB + Chroma），返回删除的断言行数。

    单关系单事务：依赖行（user_state/evidence/edge）→ 断言行 → 一起 commit
    并写 `memory_purged_at` 留痕；向量删除在 commit 后尽力执行。
    """
    ids = [
        row[0]
        for row in db.query(AiMemory.id)
        .filter(AiMemory.relation_id == relation.id)
        .all()
    ]
    for chunk in _chunks(ids):
        db.query(MemoryAssertionUserState).filter(
            MemoryAssertionUserState.assertion_id.in_(chunk)
        ).delete(synchronize_session=False)
        db.query(MemoryAssertionEvidence).filter(
            MemoryAssertionEvidence.assertion_id.in_(chunk)
        ).delete(synchronize_session=False)
        db.query(MemoryAssertionEdge).filter(
            MemoryAssertionEdge.parent_assertion_id.in_(chunk)
            | MemoryAssertionEdge.child_assertion_id.in_(chunk)
        ).delete(synchronize_session=False)
        db.query(AiMemory).filter(AiMemory.id.in_(chunk)).delete(
            synchronize_session=False
        )
    relation.memory_purged_at = _utcnow()
    db.commit()
    logger.info(
        "[MEM-PURGE] 关系记忆已清理 relation=%s rows=%s",
        relation.id, len(ids),
    )

    # DB 权威落定后，尽力清向量（失败不回滚 DB——dissolved 不可达，安全）
    if ids:
        try:
            from app.services.memory_retrieval import _get_collection

            col = _get_collection()
            if col is not None:
                for chunk in _chunks([str(i) for i in ids]):
                    col.delete(ids=chunk)
        except Exception as exc:  # noqa: BLE001——向量是派生，失败只留痕
            logger.warning(
                "[MEM-PURGE] 向量清理失败（下轮兜底）relation=%s: %s",
                relation.id, exc,
            )
    return len(ids)


def purge_due_relations(db: Session) -> int:
    """扫描并清理所有到期关系，返回处理的关系数。单关系异常互不牵连。"""
    now = _utcnow()
    rels = (
        db.query(CoupleRelation)
        .filter(
            CoupleRelation.status == "dissolved",
            CoupleRelation.memory_purged_at.is_(None),
            CoupleRelation.legal_hold.is_(False),
            CoupleRelation.memory_purge_after.isnot(None),
            CoupleRelation.memory_purge_after <= now,
        )
        .all()
    )
    done = 0
    for rel in rels:
        try:
            purge_relation_memories(db, rel)
            done += 1
        except Exception:  # noqa: BLE001——单关系失败不拖垮其余
            db.rollback()
            logger.exception("[MEM-PURGE] 清理失败 relation=%s", rel.id)
    return done


def run_purge_sweep_once() -> int:
    """开独立会话跑一轮清理（线程/测试调用入口）。"""
    from app.core.database import SessionLocal

    db = SessionLocal()
    try:
        return purge_due_relations(db)
    finally:
        db.close()


def _loop() -> None:
    while True:
        try:
            n = run_purge_sweep_once()
            if n:
                logger.info("[MEM-PURGE] 本轮清理 %s 个关系", n)
        except Exception:  # noqa: BLE001——轮次级兜底，线程不死
            logger.exception("[MEM-PURGE] 轮次异常")
        time.sleep(PURGE_SWEEP_INTERVAL_SECONDS)


def ensure_started() -> None:
    """启动清理线程（幂等）。测试 kill switch 与 pipeline worker 同款。"""
    global _purge_thread
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return
    with _purge_thread_lock:
        if _purge_thread is not None and _purge_thread.is_alive():
            return

        def _delayed() -> None:
            time.sleep(PURGE_START_DELAY_SECONDS)
            _loop()

        _purge_thread = threading.Thread(
            target=_delayed, name="mem-purge", daemon=True
        )
        _purge_thread.start()
        logger.info(
            "[MEM-PURGE] 清理线程已启动 retention=%sd interval=%ss",
            MEMORY_PURGE_RETENTION_DAYS, PURGE_SWEEP_INTERVAL_SECONDS,
        )
