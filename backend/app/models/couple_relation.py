from sqlalchemy import Boolean, String, BigInteger, DateTime, ForeignKey, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class CoupleRelation(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "couple_relation"
    __table_args__ = (
        Index("ix_couple_relation_user_a_id", "user_a_id"),
        Index("ix_couple_relation_user_b_id", "user_b_id"),
    )

    user_a_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    user_b_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)
    bind_time: Mapped[Optional[datetime]] = mapped_column(DateTime)
    unbind_requested_by: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("user.id")
    )
    unbind_requested_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    unbind_confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    # ---- 记忆治理列（v3.2 契约附录 §1.6；状态值仍 active/unbinding/dissolved）----
    #: 到达该时刻且关系 dissolved 且无 legal_hold → 系统执行 purge_relation_copy
    memory_purge_after: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    memory_purged_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    #: 争议/安全事件期间冻结任何 purge；只暂停物理清理，不恢复展示或 AI 召回
    legal_hold: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="0", nullable=False
    )

    # ---- 军师记忆沉淀总开关（记忆系统升级 P0④，关系级）----
    #: False = 服务端在 distill 入口直接阻断（不是 UI 假开关）。
    #: 只挡 AI 蒸馏（chat/会话摘要/事件蒸馏）；用户主动「计入军师记忆」的
    #: 直写不受影响——那是对既有内容的显式处置，不是 AI 替用户做决定。
    memory_distill_enabled: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="1", nullable=False
    )

    space: Mapped["CoupleSpace"] = relationship(back_populates="relation", uselist=False)
