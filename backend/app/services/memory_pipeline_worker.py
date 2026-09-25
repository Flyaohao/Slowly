"""进程内后台 Worker 线程（v3.2 §8 阶段 A 决策：零新依赖，不加 compose 服务）。

门控（D1）：
- `MEMORY_ASSERTION_DUAL_WRITE=0` → **线程永不启动**（flags 全关 = v1 行为，
  包括进程拓扑）；
- `COUPLE_DISABLE_MEMORY_DISTILL=1` → 测试 kill switch，同样不启动；
- `MEMORY_ASSERTION_INDEX_WORKER` 决定是否顺带领索引工作（DUAL_WRITE 开、
  INDEX_WORKER 关时线程仍需跑蒸馏任务，索引由 T4 直接标 skipped）。

每轮 `run_once`：领 ≤5 个蒸馏任务逐个跑完（distill → extraction → commit），
再领 1 条索引工作；单条异常互不牵连，`sleep(2.0)`。
"""
import logging
import os
import threading
from typing import Optional

from app.core import config as app_config

logger = logging.getLogger("couple.memory.worker")

#: 轮询间隔（秒）
SLEEP_SECONDS = 2.0
#: 每轮领取蒸馏任务上限（与 memory_pipeline.CLAIM_BATCH 同值）
PIPELINE_CLAIM_BATCH = 5
#: 每轮领取索引工作条数
INDEX_CLAIM_BATCH = 1

_lock = threading.Lock()
_stop_event = threading.Event()
_thread: Optional[threading.Thread] = None
#: 进程内唯一 worker 标识（写进 task.locked_by，便于排查 lease 归属）
_WORKER_ID = "w-%s" % os.getpid()


def ensure_started() -> None:
    """懒启动（D1）：DUAL_WRITE=0 或 kill switch 时是无副作用 no-op。"""
    if not app_config.MEMORY_ASSERTION_DUAL_WRITE:
        return
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return
    global _thread
    with _lock:
        if _thread is not None and _thread.is_alive():
            return
        _stop_event.clear()
        _thread = threading.Thread(
            target=_loop, name="mem-pipeline-worker", daemon=True
        )
        _thread.start()
        logger.info("[MEM-WORKER] 启动 worker_id=%s", _WORKER_ID)


def stop_for_tests() -> None:
    """测试收尾：停线程并等它退出（不幂等，重复调用安全）。"""
    global _thread
    _stop_event.set()
    with _lock:
        thread = _thread
        _thread = None
    if thread is not None and thread.is_alive():
        thread.join(timeout=10.0)


def is_running() -> bool:
    return _thread is not None and _thread.is_alive()


def run_once() -> None:
    """单轮：领 ≤5 蒸馏任务 + （INDEX_WORKER 开时）1 条索引工作。

    每条独立事务/异常——一条失败不拖垮其余。
    """
    from app.core.database import SessionLocal
    from app.services.memory_index_worker import claim_index_work, process_index_work
    from app.services.memory_pipeline import claim_next_task, process_task

    db = SessionLocal()
    try:
        for _ in range(PIPELINE_CLAIM_BATCH):
            if _stop_event.is_set():
                return
            task = claim_next_task(db, _WORKER_ID)
            if task is None:
                break
            process_task(db, task)

        if app_config.MEMORY_ASSERTION_INDEX_WORKER:
            for work in claim_index_work(db, limit=INDEX_CLAIM_BATCH):
                if _stop_event.is_set():
                    return
                process_index_work(db, work)
    finally:
        db.close()


def _loop() -> None:
    while not _stop_event.is_set():
        try:
            run_once()
        except Exception:  # noqa: BLE001——轮次级兜底，线程不死
            logger.exception("[MEM-WORKER] 轮次异常")
        _stop_event.wait(SLEEP_SECONDS)
