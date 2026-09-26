"""AI 后台生成任务（调解改写 / 重生成 / 总结）。

## 为什么需要这张表

调解链路的改写与总结此前跑在 `threading.Thread(daemon=True)` 里。守护线程不是
可靠后台任务：进程重启、worker 回收、容器发布、异常退出都会让任务凭空消失，
而会话状态已经翻到了 `rewriting` / `summarizing`——用户看到的是**永久停在
生成中**的页面，服务端却再也没有任何东西会来推进它。

持久化任务表要解决的正是这个：任务先落库、再执行；执行者崩了，租约到期后
另一个 worker（或重启后的同一个）会重新领到它。

## 状态机

    pending ──claim──▶ running ──成功──▶ succeeded
       ▲                  │
       │                  ├──失败且未耗尽──▶ pending（next_retry_at 退避后）
       └──── 用户重试 ─────┤
                          └──失败且耗尽──▶ failed（终态，会话进 *_failed）

    superseded：同一会话出现更新版本（revision 增大）时，旧任务被标记为作废，
    即使它还在重试队列里也不会再写回结果——「旧任务不得覆盖新版本结果」。

## 与 `memory_pipeline_task` 的关系

两者是**独立**的任务表：memory_pipeline 属军师记忆双写管线（v3.2 contract，
本项目 §20 禁碰区），语义、重试策略与消费者都不同。共用一张表会让两边
互相拖累（一边的重试退避会占用另一边的抢占窗口），所以这里另起一张。
"""

from datetime import datetime
from typing import Optional

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    SmallInteger,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base
from app.models.base import BigIntPKMixin, TimestampMixin

#: 任务类型
TASK_REWRITE = "mediation_rewrite"
TASK_REGENERATE = "mediation_regenerate"
TASK_SUMMARY = "mediation_summary"

TASK_TYPES = (TASK_REWRITE, TASK_REGENERATE, TASK_SUMMARY)

#: 任务状态
STATE_PENDING = "pending"
STATE_RUNNING = "running"
STATE_SUCCEEDED = "succeeded"
STATE_FAILED = "failed"
STATE_SUPERSEDED = "superseded"

#: 可被 worker 领取的状态
CLAIMABLE_STATES = (STATE_PENDING,)

#: 默认最大尝试次数（首次 + 2 次重试）。与客户端等待窗口一起定：
#: 3 次 × 120s 超时 + 退避 ≈ 6.5 分钟，Android 的总等待窗口必须覆盖它。
DEFAULT_MAX_ATTEMPTS = 3


class AiTask(BigIntPKMixin, TimestampMixin, Base):
    """一条持久化的 AI 生成任务。"""

    __tablename__ = "ai_task"
    __table_args__ = (
        # 幂等键唯一约束：同一 (类型, 会话, 版本) 只会有一行——
        # 双方同时提交时两个请求都尝试建任务，只有一个能插入成功。
        UniqueConstraint("idempotency_key", name="uk_ai_task_idempotency"),
        Index("ix_ai_task_claim", "state", "next_retry_at", "locked_until"),
        Index("ix_ai_task_session", "session_id", "task_type", "revision"),
    )

    task_type: Mapped[str] = mapped_column(String(40), nullable=False)
    session_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("ai_chat_session.id"), nullable=False
    )
    #: 触发者（重生成任务据此判断「改哪一侧」，可空：系统补偿任务无触发者）
    requested_by_user_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=True
    )
    #: 会话版本号，取自 `ai_chat_session.mediation_revision`。任务只写回
    #: 版本仍等于自己的结果；会话版本被推进后，旧任务作废。
    revision: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    #: SHA256(task_type | session_id | revision)，唯一约束的载体
    idempotency_key: Mapped[str] = mapped_column(String(64), nullable=False)
    state: Mapped[str] = mapped_column(String(20), nullable=False, default=STATE_PENDING)

    attempt: Mapped[int] = mapped_column(
        SmallInteger, default=0, server_default="0", nullable=False
    )
    max_attempts: Mapped[int] = mapped_column(
        SmallInteger, default=DEFAULT_MAX_ATTEMPTS, nullable=False
    )
    #: 退避窗口；到点前不可被领取
    next_retry_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    #: 最近一次失败原因（截断到 480 字符，足够定位且不会把模型原文灌进日志表）
    last_error: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)

    #: 任务载荷：只放**ID 引用**，不放用户倾诉原文（隐私红线：敏感正文不冗余存储）
    payload: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)

    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    #: 执行中心跳（长任务可据此判断「执行者还活着」而不是只靠租约到期）
    heartbeat_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)

    locked_by: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    locked_until: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
