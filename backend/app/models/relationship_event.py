import datetime as dt
from typing import Optional

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKey,
    Index,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import BigIntPKMixin, Base

#: 作用性质白名单。收敛成两档而不是自由文本，是因为它的唯一用途是
#: 让军师判断这条事件该往哪个方向解读——自由文本等于没法判定。
POLARITY_POSITIVE = "positive"
POLARITY_NEGATIVE = "negative"
POLARITY_VALUES = (POLARITY_POSITIVE, POLARITY_NEGATIVE)


class RelationshipEvent(BigIntPKMixin, Base):
    """关系历史事件：用户手动记下的「哪天发生了什么」。

    与 `anniversary` 的区别是**语义**：纪念日是可重复的日期锚点（生日、
    在一起纪念日），本表是一条**一次性发生过的具体事件**（哪天吵了一架、
    哪天一起逛街发生了什么），带发生时刻、经过与作用判定。

    ## 为什么 `reason` / `polarity` 是 NOT NULL

    这是本表存在的意义，不是可选装饰。

    孤立的事件记录对 AI 是**没有上下文的噪声**：同一条「他没回我消息」，
    在「对方刚失业」和「对方在冷暴力」两种语境下，军师该给的建议完全相反。
    如果事件可以没有原因就进入 AI 上下文，军师就会拿一条孤立的负面记录去
    推断关系状态，把偶发当成模式 —— 那正是「污染」。

    因此把「写入原因」提升为**落库门槛**：
    `reason` 必须说清这件事对两人关系起到的明确作用；
    `polarity` 把作用方向收敛成积极 / 消极两档。
    校验不通过的事件**根本不允许写入**（见 `relationship_event_service`）。
    """

    __tablename__ = "relationship_event"
    __table_args__ = (
        Index("ix_relationship_event_relation_id", "relation_id"),
        Index("ix_relationship_event_time", "event_time"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )
    #: 事件标题：一句话说清发生了什么
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    #: 发生时刻（用户要求精确到「哪天什么时候」）
    event_time: Mapped[dt.datetime] = mapped_column(DateTime, nullable=False)
    #: 经过：可选补充
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    #: 写入原因：这件事对情侣关系起到的明确作用（**必填**）
    reason: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="写入原因：这件事对情侣关系起到的明确作用",
    )
    #: 作用性质：positive / negative
    polarity: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        comment="作用性质：positive=积极 / negative=消极",
    )
    #: 记录人（双方都可写，留痕便于区分视角）
    created_by_user_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, nullable=True
    )
    created_at: Mapped[dt.datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
