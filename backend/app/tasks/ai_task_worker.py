"""AI 后台任务 worker（常驻进程）。

## 为什么是一个独立容器

调解的改写/总结都是分钟级的 LLM 调用。它们不能跑在 API 进程的请求线程里
（用户等不起），也不能只靠进程内守护线程（进程一重启任务就没了，会话永久
停在生成中）。任务落库之后需要一个**会一直在场的执行者**：就是本进程。

与 `tasks-scheduler` 的关系：那边跑的是定时业务（解绑冷静期），这边跑的是
队列消费。两者共用镜像、各自 entrypoint，互不影响——队列堵了不会拖慢定时任务，
反之亦然。

## 启动方式

    python -m app.tasks.ai_task_worker

compose 里由 `ai-task-worker` 服务承载（与 backend 同镜像）。

## 恢复语义

启动时先 `recover_stale_tasks()`：把上次进程被杀时留在 `running` 的任务
（租约已过期）打回 `pending`，然后进入正常巡回。这样「重启后未完成任务
自动恢复」不依赖任何人工干预。
"""

import logging
import os
import signal
import time

from app.core.database import SessionLocal
from app.services import ai_task_service

logger = logging.getLogger("couple.ai.worker")

#: 空闲时的巡回间隔（秒）。任务入队时 API 进程还会 `kick()` 一脚，
#: 所以这里的间隔只决定「最坏情况下多久被发现」，不需要很短。
IDLE_INTERVAL_SECONDS = float(os.getenv("AI_TASK_IDLE_INTERVAL", "3"))

#: 每轮处理上限
BATCH = int(os.getenv("AI_TASK_BATCH", "5"))

_running = True


def _stop(signum, frame):  # noqa: ARG001
    global _running
    _running = False
    logger.info("收到信号 %s，worker 准备退出", signum)


def run_once() -> int:
    """一次巡回：先回收僵尸任务，再执行到期任务。返回执行数量。"""
    db = SessionLocal()
    try:
        recovered = ai_task_service.recover_stale_tasks(db)
        if recovered:
            logger.warning("回收了 %s 个中断任务（worker 重启/租约过期）", recovered)
        return ai_task_service.run_due_tasks(db, worker_id=ai_task_service.WORKER_ID, limit=BATCH)
    finally:
        db.close()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    signal.signal(signal.SIGTERM, _stop)
    try:
        signal.signal(signal.SIGINT, _stop)
    except (ValueError, OSError):  # 非主线程/Windows 平台的兼容处理
        pass

    logger.info("AI 任务 worker 启动 worker_id=%s", ai_task_service.WORKER_ID)
    while _running:
        try:
            done = run_once()
        except Exception as exc:  # noqa: BLE001  单轮失败不能打死 worker
            logger.error("worker 巡回异常：%s", exc, exc_info=True)
            done = 0
            time.sleep(IDLE_INTERVAL_SECONDS)
            continue
        if done == 0:
            time.sleep(IDLE_INTERVAL_SECONDS)

    logger.info("AI 任务 worker 已退出")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
