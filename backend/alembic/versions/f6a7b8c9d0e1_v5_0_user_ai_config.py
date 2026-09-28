"""v5.0 用户级 AI 配置表族

新增：
- `user_ai_config`  每用户一行：协议(openai/anthropic)、端点、模型、
                    embedding 配置、限流开关、key 轮换游标
- `user_ai_key`     每用户多把 api-key（Fernet 加密落库），按 id 轮转

需求背景：军师/AI 能力的大模型 API 信息与单个用户严格绑定，
设置页配置，未配置用户 AI 功能不可用（强制配置，D4）。

Revision ID: f6a7b8c9d0e1
Revises: b7c8d9e0f1a2
Create Date: 2026-09-28 16:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f6a7b8c9d0e1'
down_revision: Union[str, None] = 'b7c8d9e0f1a2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _tables() -> list:
    return sa.inspect(op.get_bind()).get_table_names()


def upgrade() -> None:
    existing = _tables()

    if "user_ai_config" not in existing:
        op.create_table(
            "user_ai_config",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), nullable=False),
            sa.Column("provider_type", sa.String(length=20),
                      server_default="openai", nullable=False),
            sa.Column("base_url", sa.String(length=512), nullable=False),
            sa.Column("model_name", sa.String(length=128), nullable=False),
            sa.Column("embedding_base_url", sa.String(length=512), nullable=True),
            sa.Column("embedding_model", sa.String(length=128), nullable=True),
            sa.Column("embedding_api_key_enc", sa.String(length=512), nullable=True),
            sa.Column("embedding_dim", sa.Integer(), nullable=True),
            sa.Column("enable_rate_limit", sa.Boolean(),
                      server_default=sa.text("1"), nullable=False),
            sa.Column("key_cursor", sa.SmallInteger(),
                      server_default="0", nullable=False),
            sa.Column("created_at", sa.DateTime(),
                      server_default=sa.text("now()"), nullable=False),
            sa.Column("updated_at", sa.DateTime(),
                      server_default=sa.text("now()"), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["user.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint("user_id", name="uk_user_ai_config_user"),
        )

    if "user_ai_key" not in existing:
        op.create_table(
            "user_ai_key",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), nullable=False),
            sa.Column("config_id", sa.BigInteger(), nullable=False),
            sa.Column("key_enc", sa.String(length=512), nullable=False),
            sa.Column("label", sa.String(length=50), nullable=True),
            sa.Column("enabled", sa.Boolean(),
                      server_default=sa.text("1"), nullable=False),
            sa.Column("last_error_code", sa.String(length=64), nullable=True),
            sa.Column("last_error_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(),
                      server_default=sa.text("now()"), nullable=False),
            sa.Column("updated_at", sa.DateTime(),
                      server_default=sa.text("now()"), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["user.id"]),
            sa.ForeignKeyConstraint(["config_id"], ["user_ai_config.id"]),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_user_ai_key_user_id", "user_ai_key", ["user_id"])


def downgrade() -> None:
    bind = op.get_bind()
    names = sa.inspect(bind).get_table_names()
    if "user_ai_key" in names:
        op.drop_table("user_ai_key")
    if "user_ai_config" in names:
        op.drop_table("user_ai_config")
