from sqlalchemy import String, Text, BigInteger, Index
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class SafetyEvent(BigIntPKMixin, TimestampMixin, Base):
    """安全护栏审计事件：输入/输出被拦截或命中风险时记录，供回查与误杀分析。

    审计是旁路能力：写失败只打日志，绝不影响主链路（见 safety_repo.log_event）。
    """

    __tablename__ = "safety_event"
    __table_args__ = (
        Index("ix_safety_event_user_id", "user_id"),
    )

    #: 匿名可空：部分链路（如回调场景）可能拿不到用户
    user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: 业务场景：chat / chat_stream / mediation / agent / letter ...
    scene: Mapped[str] = mapped_column(String(50), default="chat", nullable=False)
    #: input=用户输入被拦截；output=模型输出命中
    source: Mapped[str] = mapped_column(String(10), default="input", nullable=False)
    risk_level: Mapped[str] = mapped_column(String(30), nullable=False)
    #: 命中词 JSON 数组字符串，如 ["滚", "废物"]
    hit_keywords: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
