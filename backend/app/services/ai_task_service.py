"""AI 后台任务的编排层：入队、领取、执行、失败重试、终态收口。

## 这一层解决什么

调解的改写/重生成/总结都是**分钟级**的 LLM 调用（单次超时 120s，结构化链路
还会重试）。它们不能：

- 跑在 HTTP 请求线程里（用户等 120s 才拿到响应）；
- 跑在「只有守护线程、没有落库」的机制里（进程重启即丢，会话永久停在生成中）。

所以：**请求只负责入队，执行由 worker 承担，任务的可见状态在数据库里**。
入队那一刻任务就已落库，即使进程此刻被杀，重启后 worker 会把它捞回来。

## 领取与租约

`claim_next_task` 用条件 UPDATE 原子抢占（见 `ai_task_repo`）：两个 worker
同时抢同一行只有一个能拿到。领取时立刻 `attempt += 1` 并 commit，
LLM 调用期间**不持有事务/行锁**。租约（默认 600s）覆盖单次 LLM 上限，
执行者崩溃后租约到期 → `recover_stale_tasks` 打回 pending → 重新执行。

## 幂等

- 同一 `(task_type, session_id, revision)` 只有一行（唯一约束）；
- 重试是同一行的 `attempt` 自增，不是新任务；
- 结果写回前校验 `session.mediation_revision == task.revision`，
  不匹配（会话已被更新版本推进）就丢弃结果，杜绝旧任务覆盖新版本。

## session 归属

worker 每执行一个任务用独立 Session（`SessionLocal`），失败即回滚重来；
调用方（请求线程）传入的 db 只用于入队。
"""

from __future__ import annotations

import logging
import os
import socket
import threading
import uuid
from datetime import datetime
from typing import Callable, Dict, List, Optional

from sqlalchemy import and_, or_, update
from sqlalchemy.orm import Session

from app.core.database import SessionLocal
from app.models.ai import AiChatSession
from app.models.ai_task import (
    DEFAULT_MAX_ATTEMPTS,
    STATE_FAILED,
    STATE_PENDING,
    STATE_RUNNING,
    STATE_SUCCEEDED,
    STATE_SUPERSEDED,
    TASK_REGENERATE,
    TASK_REWRITE,
    TASK_SUMMARY,
    AiTask,
)
from app.repositories import ai_task_repo

logger = logging.getLogger("couple.ai.task")

#: 重试退避基数（秒）：第 n 次失败后退避 = BASE * 2^(n-1)。
#: 3 次尝试的总等待窗口 ≈ 30 + 60 ≈ 90s + 单次 120s 超时，客户端据此设上限。
RETRY_BASE_BACKOFF_SECONDS = 30

#: 每次 worker 巡回最多处理多少个任务（避免一次巡回把进程占满）
DRAIN_BATCH = 5

#: 单进程标识：worker 用它抢任务，出问题时能从 locked_by 直接定位到进程。
WORKER_ID = "%s-%s-%s" % (socket.gethostname(), os.getpid(), uuid.uuid4().hex[:8])

#: 是否在请求线程里「踢一脚」worker。
#:
#: **这只是加速，不是可靠性来源**——任务在 `kick()` 之前已经落库，
#: 即使这一脚没踢出去（进程下一秒就崩），调度器容器也会把它捞起来。
#: 置 False 的场景：hermetic 测试（不希望后台线程干扰断言）与
#: 「验证调度器路径」的测试（只允许 `run_due_tasks` 执行任务）。
AUTO_KICK = True

#: 会话工厂。默认是生产的 `SessionLocal`；hermetic 测试把它换成内存库的
#: sessionmaker（同 `test_mediation_identity` 对 `core.database.SessionLocal`
#: 的做法），这样 `kick()` 起来的后台线程也只会碰内存库。
_SESSION_FACTORY = SessionLocal


def use_session_factory(factory) -> None:
    """测试钩子：把后台线程用的会话工厂换成测试库。生产路径不调用。"""
    global _SESSION_FACTORY
    _SESSION_FACTORY = factory


def kick() -> None:
    """请求线程里异步催一下 worker（fire-and-forget，失败只记日志）。"""
    if not AUTO_KICK:
        return

    def _entry() -> None:
        db = _SESSION_FACTORY()
        try:
            run_due_tasks(db, worker_id=WORKER_ID, limit=DRAIN_BATCH)
        except Exception:  # noqa: BLE001
            logger.warning("后台任务巡回失败", exc_info=True)
        finally:
            db.close()

    threading.Thread(target=_entry, name="ai-task-kick", daemon=True).start()


# --------------------------------------------------------------------------- #
# 入队
# --------------------------------------------------------------------------- #

def ensure_task(
    db: Session,
    session: AiChatSession,
    task_type: str,
    *,
    requested_by_user_id: Optional[int] = None,
    payload: Optional[dict] = None,
    max_attempts: int = DEFAULT_MAX_ATTEMPTS,
) -> AiTask:
    """为会话的**当前版本**建任务（幂等），并清空上一次的失败标记。

    幂等由 `(task_type, session_id, revision)` 唯一约束保证：双方同时提交时
    两个请求都会走到这里，但只有一个任务行存在——这正是「双方同时提交只创建
    一个改写任务」。
    """
    if session.mediation_revision is None:
        session.mediation_revision = 0
    task = ai_task_repo.create_task(
        db,
        task_type=task_type,
        session_id=session.id,
        revision=int(session.mediation_revision),
        requested_by_user_id=requested_by_user_id,
        payload=payload,
        max_attempts=max_attempts,
    )
    if task_type == TASK_SUMMARY:
        session.summary_task_id = task.id
    else:
        session.rewrite_task_id = task.id
    # 新任务出现 = 上一次的失败结论作废（用户看到的是「正在重试」而不是「失败」）
    session.mediation_failure_code = None
    session.mediation_last_error = None
    session.mediation_failed_at = None
    db.commit()
    return task


# --------------------------------------------------------------------------- #
# 执行
# --------------------------------------------------------------------------- #

def run_due_tasks(
    db: Session, worker_id: Optional[str] = None, limit: int = DRAIN_BATCH
) -> int:
    """执行至多 `limit` 个到期任务，返回真正执行了几个。

    这是 worker 与「测试驱动」共用的**唯一执行入口**——生产路径没有任何
    「内联生成」快捷方式，测试走的也是这条路径。

    每个任务跑在自己的 Session 上（`_SESSION_FACTORY`）：单个任务失败后的
    `rollback` 不会波及同一轮里的其它任务，也不会把 worker 自己的会话弄脏。
    传入的 `db` 只用于「领取」这一步。
    """
    worker = worker_id or WORKER_ID
    done = 0
    for _ in range(limit):
        task = ai_task_repo.claim_next_task(db, worker)
        if task is None:
            break
        task_id = task.id
        task_db = _SESSION_FACTORY()
        try:
            task = ai_task_repo.get_task_by_id(task_db, task_id)
            if task is not None:  # 领取后被删（理论上不会发生）就跳过
                execute_task(task_db, task)
        finally:
            task_db.close()
        done += 1
    return done


def execute_task(db: Session, task: AiTask) -> bool:
    """执行单个已领取的任务。返回是否成功。异常绝不外抛（worker 不能被单任务拖垮）。"""
    try:
        handler = _handler_for(task.task_type)
        handler(db, task)
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "AI 任务失败 task=%s type=%s attempt=%s/%s: %s",
            task.id, task.task_type, task.attempt, task.max_attempts, exc,
        )
        try:
            db.rollback()
        except Exception:  # noqa: BLE001
            pass
        _record_failure(db, task, exc)
        return False


def _handler_for(task_type: str) -> Callable[[Session, AiTask], None]:
    """按类型取处理器。**延迟导入** mediation_service：它反过来要 import 本模块。"""
    from app.services import mediation_service

    handlers = {
        TASK_REWRITE: mediation_service.run_rewrite_task,
        TASK_REGENERATE: mediation_service.run_regenerate_task,
        TASK_SUMMARY: mediation_service.run_summary_task,
    }
    handler = handlers.get(task_type)
    if handler is None:
        raise ValueError("未知任务类型：%s" % task_type)
    return handler


def _record_failure(db: Session, task: AiTask, exc: Exception) -> None:
    """失败收口：未耗尽 → 退避重排；耗尽 → 终态失败 + 会话进明确失败态。

    两条路径都先确认**任务行仍归本次执行所有**（`schedule_retry` /
    `mark_failed` 返回 False 表示已被回收或已被别的 worker 接管）。
    归属已经变了就什么都不写：那种情况下真正的那次执行会自己收口，
    旧执行者的结论只会制造状态来回跳。
    """
    attempts = int(task.attempt or 0)
    message = _describe_error(exc)

    if attempts >= int(task.max_attempts or DEFAULT_MAX_ATTEMPTS):
        if not ai_task_repo.mark_failed(db, task, message):
            logger.info(
                "任务行已不归本执行者，跳过终态收口 task=%s", task.id
            )
            return
        mark_session_failure(db, task, message, terminal=True)
        logger.error(
            "AI 任务重试耗尽 task=%s session=%s type=%s attempts=%s",
            task.id, task.session_id, task.task_type, attempts,
        )
        return
    backoff = RETRY_BASE_BACKOFF_SECONDS * (2 ** max(attempts - 1, 0))
    if not ai_task_repo.schedule_retry(db, task, message, backoff):
        logger.info("任务行已不归本执行者，跳过退避重排 task=%s", task.id)
        return
    mark_session_failure(db, task, message, terminal=False)
    logger.info(
        "AI 任务退避重排 task=%s attempt=%s next=%ss", task.id, attempts, backoff
    )


def _describe_error(exc: Exception) -> str:
    """失败原因：只留类型与短消息。

    **不写用户正文**：任务的输入是双方的倾诉原文，落到日志/错误列里等于
    在数据库里再存一份敏感文本（隐私红线）。
    """
    text = str(exc).strip().replace("\n", " ")
    if len(text) > 200:
        text = text[:200] + "…"
    return "%s: %s" % (type(exc).__name__, text) if text else type(exc).__name__


# --------------------------------------------------------------------------- #
# 会话侧失败状态
# --------------------------------------------------------------------------- #

#: 任务类型 → 该类型失败时会话应处的状态。
#: 「不要把失败伪装成一直 processing」：processing 只属于真的还在跑的时候。
FAILURE_STATUS_BY_TASK: Dict[str, str] = {
    TASK_REWRITE: "rewrite_failed",
    TASK_REGENERATE: "rewrite_failed",
    TASK_SUMMARY: "summary_failed",
}


def mark_session_failure(
    db: Session, task: AiTask, message: str, *, terminal: bool
) -> None:
    """把失败写进**会话**（客户端读 GET {id} 就能看到，不必翻任务表）。

    可重试与终态用 `mediation_failure_code` 区分：
    `TASK_RETRY_SCHEDULED`（还会自动重试）/ `TASK_EXHAUSTED`（要用户手动重试）。
    两种都允许用户点「重试」——区别只在文案与是否还值得等。
    """
    status = FAILURE_STATUS_BY_TASK.get(task.task_type)
    if status is None:
        return
    code = "TASK_EXHAUSTED" if terminal else "TASK_RETRY_SCHEDULED"
    try:
        db.execute(
            update(AiChatSession)
            .where(
                AiChatSession.id == task.session_id,
                # 会话已经推进过（比如用户手动重试成功）→ 不要用旧失败覆盖新状态
                AiChatSession.mediation_revision == task.revision,
            )
            .values(
                mediation_status=status,
                mediation_failure_code=code,
                mediation_last_error=message[:480],
                mediation_failed_at=datetime.utcnow(),
            )
        )
        db.commit()
    except Exception:  # noqa: BLE001
        logger.warning("写会话失败状态失败 session=%s", task.session_id, exc_info=True)
        db.rollback()
        return

    _broadcast_failure(db, task.session_id, status)


def _broadcast_failure(db: Session, session_id: int, status: str) -> None:
    """状态帧广播（失败也要让在线的另一半立刻看到，而不是等轮询）。"""
    try:
        from app.services import mediation_service

        mediation_service.broadcast_status_by_id(db, session_id, status)
    except Exception:  # noqa: BLE001
        logger.warning("失败状态广播失败 session=%s", session_id, exc_info=True)


__all__ = [
    "AUTO_KICK",
    "WORKER_ID",
    "RETRY_BASE_BACKOFF_SECONDS",
    "STATE_FAILED",
    "STATE_PENDING",
    "STATE_RUNNING",
    "STATE_SUCCEEDED",
    "STATE_SUPERSEDED",
    "TASK_REGENERATE",
    "TASK_REWRITE",
    "TASK_SUMMARY",
    "ensure_task",
    "execute_task",
    "kick",
    "mark_session_failure",
    "run_due_tasks",
    "use_session_factory",
]
