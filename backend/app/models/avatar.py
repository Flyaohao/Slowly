from sqlalchemy import String, BigInteger, Boolean, ForeignKey, JSON, DateTime, Index, func
from sqlalchemy.orm import Mapped, mapped_column
from datetime import datetime
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class AiAvatar(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "ai_avatar"
    __table_args__ = (
        Index("ix_ai_avatar_relation_id", "relation_id"),
    )

    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), unique=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(50), default="小爱", nullable=False)
    body_color: Mapped[str] = mapped_column(String(20), default="#FFB6C1", nullable=False)
    face_config: Mapped[Optional[dict]] = mapped_column(JSON)
    outfit_config: Mapped[Optional[dict]] = mapped_column(JSON)
    voice_style: Mapped[str] = mapped_column(String(30), default="gentle", nullable=False)
    #: P-B §4.4：语气来源。auto=由画像自动选择（默认），manual=用户手选过。
    #: 画像驱动的自动重算是 P-D，本轮只建列 + 读写提示。
    voice_style_source: Mapped[str] = mapped_column(
        String(10), default="auto", nullable=False, server_default="auto"
    )
    background_url: Mapped[Optional[str]] = mapped_column(String(500))

    # ---- 契约 §3.3 军师设置（与 /api/v1/advisor/settings 同构的 4 个新真列）----
    #: 军师对用户的称呼；NULL/空串 → 对外口径为 ""（FE 兜底默认）
    address_name: Mapped[Optional[str]] = mapped_column(String(50))
    #: 详细程度 brief|standard|detailed
    detail_level: Mapped[str] = mapped_column(
        String(10), default="standard", nullable=False, server_default="standard"
    )
    #: 主动程度 passive|moderate|active
    proactivity: Mapped[str] = mapped_column(
        String(10), default="moderate", nullable=False, server_default="moderate"
    )
    #: 是否显示判断依据（prompt 注入 + evidence 面板共用这一开关）
    show_evidence: Mapped[bool] = mapped_column(
        Boolean, default=True, nullable=False, server_default="1"
    )


class AiAvatarAsset(BigIntPKMixin, Base):
    __tablename__ = "ai_avatar_asset"

    asset_type: Mapped[str] = mapped_column(String(30), nullable=False)
    asset_url: Mapped[str] = mapped_column(String(500), nullable=False)
    unlock_condition: Mapped[Optional[str]] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
