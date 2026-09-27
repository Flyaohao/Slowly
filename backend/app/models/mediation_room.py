"""共同调解室（2026-09-28 设计文档 §九）表族。

与旧调解链路（ai_chat_session 的 mediation 状态机 + 双视角汇总）的关系：
旧链路改名「各自的看法」并整体保留，本表族是**新三人房间**模型——

- `mediation_room`         房间（状态机 + 应答/结束投票 + 滚动摘要 + 结算租约）
- `room_message`           三方消息（sender 维度：user_a / user_b / advisor）
- `mediation_room_pending` 房间级 pending 生成互斥（room_id 唯一约束 = 数据库级 mutex）
- `mediation_event`        结算时结构化落库的吵架事件（观察系统素材，§七.2）

设计要点（对应设计文档条款）：

- 状态机三段式（§三）：active（在席/靠边，靠边由 advisor_phase 表达）
  → settling（双方点了结束，worker 生成调解书）→ settlement_ready（待双方确认）
  → settled（已结算，房间变档案）。
- 军师发言走 API 进程 SSE（D-CHANNEL），写回 = 消息 INSERT + 状态推进同一 commit；
  结算/摘要压缩是非交互任务，走 ai-task-worker（本表族自带租约字段，不复用
  ai_task 表——那张表的 session_id 是指向 ai_chat_session 的强外键，房间没有
  会话行，硬塞锚定行会污染会话列表）。
- 全文只落库（room_message 本身就是全文），prompt 只带滚动摘要 + 新消息窗口（§四）。
"""

from datetime import datetime
from typing import Optional

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base
from app.models.base import BigIntPKMixin, TimestampMixin

# --------------------------------------------------------------------------- #
# 常量（状态机与角色枚举的唯一来源）
# --------------------------------------------------------------------------- #

#: 房间状态
ROOM_ACTIVE = "active"                    # 在席期/靠边期（靠边看 advisor_phase）
ROOM_SETTLING = "settling"                # 双方已投结束票，worker 生成调解书中
ROOM_SETTLEMENT_READY = "settlement_ready"  # 调解书已生成，待双方确认
ROOM_SETTLED = "settled"                  # 已结算（档案）

ROOM_STATUSES = (ROOM_ACTIVE, ROOM_SETTLING, ROOM_SETTLEMENT_READY, ROOM_SETTLED)

#: 军师阶段：engaged=在席（@应答）；aside=靠边（本轮沉默，@ 可召回）
PHASE_ENGAGED = "engaged"
PHASE_ASIDE = "aside"

#: 消息发送方维度
SENDER_USER_A = "user_a"
SENDER_USER_B = "user_b"
SENDER_ADVISOR = "advisor"

#: 调解书结果三态（D-RESULT：和解 / 搁置 / 冷战）
RESULT_RECONCILED = "reconciled"
RESULT_DEFERRED = "deferred"
RESULT_COLD_WAR = "cold_war"
RESULT_LABELS = {
    RESULT_RECONCILED: "和解",
    RESULT_DEFERRED: "搁置",
    RESULT_COLD_WAR: "冷战",
}

#: 任务类型名（worker 巡回按状态+租约领房间，不走 ai_task 表）
SETTLE_LEASE_SECONDS = 600
SETTLE_MAX_ATTEMPTS = 3

#: pending 生成有效期（秒）：流式端点超龄视为孤儿，允许重新召唤
PENDING_TTL_SECONDS = 300


class MediationRoom(BigIntPKMixin, TimestampMixin, Base):
    """一间共同调解室。"""

    __tablename__ = "mediation_room"
    __table_args__ = (
        Index("ix_mediation_room_relation_status", "relation_id", "status"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    creator_user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )

    # ---- 事件卡（创建时一次成型，是军师的静态决策材料，§二）----
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    event_time: Mapped[str] = mapped_column(String(64), nullable=False)
    cause_text: Mapped[str] = mapped_column(Text, nullable=False)
    process_text: Mapped[str] = mapped_column(Text, nullable=False)
    current_text: Mapped[str] = mapped_column(Text, nullable=False)
    style_key: Mapped[str] = mapped_column(String(40), nullable=False)

    # ---- 状态机（§三）----
    status: Mapped[str] = mapped_column(
        String(20), default=ROOM_ACTIVE, nullable=False
    )
    advisor_phase: Mapped[str] = mapped_column(
        String(20), default=PHASE_ENGAGED, nullable=False
    )
    #: 军师发言轮次（每轮发言 +1；应答/投票都挂在「本轮」上）
    round_no: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    #: 军师发言后等待双方应答（交互弹窗，§五）
    waiting_reply: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    agree_user_a: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    agree_user_b: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    # ---- 结束调解投票（双方各点、可反悔，D-CLOSING）----
    end_vote_user_a: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    end_vote_user_b: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    # ---- 滚动摘要（§四 token 策略：摘要定长，原文窗口有界）----
    advisor_summary: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    #: 摘要已覆盖到的消息水位（该 id 及之前的消息已并入摘要）
    summary_upto_message_id: Mapped[Optional[int]] = mapped_column(BigInteger)
    #: worker 压缩任务标记（窗口超阈值时置位）
    summary_pending: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    # ---- 最近消息水位（短轮询排序/7 天提醒用）----
    last_message_id: Mapped[Optional[int]] = mapped_column(BigInteger)
    last_message_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    # ---- 调解书与结算（D-RESULT：军师判定 + 双方确认）----
    #: 调解书 JSON：{result, summary_text, agreements[], responsibilities{a,b}}
    settlement: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    result: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    confirm_user_a: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    confirm_user_b: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    settled_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    # ---- 结算租约（worker 领取；不用 ai_task 表，原因见模块 docstring）----
    settle_attempt: Mapped[int] = mapped_column(
        SmallInteger, default=0, server_default="0", nullable=False
    )
    settle_locked_by: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    settle_locked_until: Mapped[Optional[datetime]] = mapped_column(DateTime)
    settle_next_retry_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    settle_last_error: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)


class RoomMessage(BigIntPKMixin, TimestampMixin, Base):
    """房间内的一条消息。三方可见、永久保存（§三 微信群模型）。"""

    __tablename__ = "room_message"
    __table_args__ = (
        Index("ix_room_message_room_id", "room_id"),
    )

    room_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("mediation_room.id"), nullable=False
    )
    sender_type: Mapped[str] = mapped_column(String(20), nullable=False)
    #: user 消息的作者 id；advisor 消息为 NULL
    sender_user_id: Mapped[Optional[int]] = mapped_column(BigInteger)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    #: advisor 消息的思考过程（回放折叠展示）
    thinking: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    #: assistant 消息必落风险等级（沿用调解链路红线）
    risk_level: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    #: advisor 消息所属轮次
    round_no: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)


class MediationRoomPending(BigIntPKMixin, TimestampMixin, Base):
    """房间级 pending 生成互斥（§四：同一房间至多一个进行中的军师生成）。

    `room_id` 唯一约束就是 mutex 本身：并发 INSERT 只有一个成功，
    失败方拿到「军师正在思考」。token 由 claim 成功方持有，
    流式端点凭 token 认领生成权（另一个客户端拿不到 token 就开不了流）。
    """

    __tablename__ = "mediation_room_pending"
    __table_args__ = (
        UniqueConstraint("room_id", name="uk_room_pending_room"),
    )

    room_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("mediation_room.id"), nullable=False
    )
    kind: Mapped[str] = mapped_column(String(20), default="advisor_reply", nullable=False)
    token: Mapped[str] = mapped_column(String(64), nullable=False)
    created_by_user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)


class MediationEvent(BigIntPKMixin, TimestampMixin, Base):
    """结算时结构化落库的吵架事件（§七.2，观察系统最高价值素材）。"""

    __tablename__ = "mediation_event"
    __table_args__ = (
        Index("ix_mediation_event_relation", "relation_id"),
    )

    room_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("mediation_room.id"), nullable=False
    )
    relation_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    event_time: Mapped[str] = mapped_column(String(64), nullable=False)
    cause_text: Mapped[str] = mapped_column(Text, nullable=False)
    process_text: Mapped[str] = mapped_column(Text, nullable=False)
    #: 和解 / 搁置 / 冷战
    result: Mapped[str] = mapped_column(String(20), nullable=False)
    settled_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
