"""用户级 AI 配置模型（v5.0）

设计要点
--------
- ``user_ai_config``：每用户一行（user_id 唯一），存放协议、端点、模型与
  embedding 配置。**与 user 严格绑定**（FK + unique）。
- ``user_ai_key``：每用户多把 api-key（D8 轮换），按 id 顺序轮转；
  单把配额/鉴权失败自动切下一把。

**为什么 api-key 不放 user 表**：轮换要求一对多，user 表加列做不到；
其余配置放独立表也是同理（一个用户一行，语义上就是 user 的 1:1 扩展表）。
"""

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, SmallInteger, String
from sqlalchemy.orm import Mapped, mapped_column, relationship
from datetime import datetime

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class UserAiConfig(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "user_ai_config"

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), unique=True, nullable=False
    )
    #: openai = OpenAI 兼容协议（DashScope/各类中转）；anthropic = Anthropic Messages 协议
    provider_type: Mapped[str] = mapped_column(
        String(20), default="openai", nullable=False
    )
    base_url: Mapped[str] = mapped_column(String(512), nullable=False)
    model_name: Mapped[str] = mapped_column(String(128), nullable=False)

    # ---- embedding（用户级，D5）----
    #: 为空时 chat 与 embedding 同端点同 key（OpenAI 兼容场景的常态）；
    #: anthropic 协议没有 embedding API，此项必填且必须为 OpenAI 兼容端点
    embedding_base_url: Mapped[str | None] = mapped_column(String(512))
    embedding_model: Mapped[str | None] = mapped_column(String(128))
    embedding_api_key_enc: Mapped[str | None] = mapped_column(String(512))
    #: 保存时实测并锁定的向量维度；与既有 Chroma 集合(1024)不一致则拒绝保存
    embedding_dim: Mapped[int | None] = mapped_column(Integer)

    # ---- 限流开关（D6：用户自选）----
    enable_rate_limit: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="1", nullable=False
    )

    # ---- key 轮换游标（round-robin）----
    key_cursor: Mapped[int] = mapped_column(SmallInteger, default=0, nullable=False)

    keys: Mapped[list["UserAiKey"]] = relationship(
        back_populates="config",
        cascade="all, delete-orphan",
        order_by="UserAiKey.id",
    )

    @property
    def updated_at_dt(self) -> datetime | None:
        return self.updated_at


class UserAiKey(BigIntPKMixin, TimestampMixin, Base):
    __tablename__ = "user_ai_key"

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    config_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user_ai_config.id"), nullable=False
    )
    key_enc: Mapped[str] = mapped_column(String(512), nullable=False)
    label: Mapped[str | None] = mapped_column(String(50))
    enabled: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="1", nullable=False
    )
    #: 最近一次该 key 触发的可轮换错误（配额/鉴权），供设置页展示
    last_error_code: Mapped[str | None] = mapped_column(String(64))
    last_error_at: Mapped[datetime | None] = mapped_column(DateTime)

    config: Mapped["UserAiConfig"] = relationship(back_populates="keys")
