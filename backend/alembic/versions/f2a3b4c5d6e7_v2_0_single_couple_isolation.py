"""v2.0 单身 / 情侣模式隔离

Revision ID: f2a3b4c5d6e7
Revises: d5e6f7a8b9c0
Create Date: 2026-09-14 20:30:00.000000

背景
----
v2.0 引入了"单身模式 / 情侣模式"的目录级隔离，后端新增了 4 张表和 user.has_couple。
但这一批变更此前只存在于代码里：表是当时用临时脚本直接在库中建出来的，
迁移链里没有任何记录。带来两个后果：

1. 全新环境执行 `alembic upgrade head` 建不出这几张表，单身模式相关功能直接报错；
2. `alembic revision --autogenerate` 因为元数据里没有它们，会反过来提议把它们删掉。

本迁移把这段历史正式补进链中。

幂等性说明
----------
MySQL 不支持事务性 DDL，且历史库中这些表已被临时脚本建出。
因此所有建表/加列都先做存在性判断，保证 `alembic upgrade head`
在**全新库**和**已有历史库**上都能安全执行。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f2a3b4c5d6e7'
down_revision: Union[str, None] = 'd5e6f7a8b9c0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def _has_column(table: str, column: str) -> bool:
    insp = sa.inspect(op.get_bind())
    if not insp.has_table(table):
        return False
    return column in {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    # ---------- 1. 私人日记（单身模式的倾诉出口） ----------
    if not _has_table("diary_entry"):
        op.create_table(
            "diary_entry",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("title", sa.String(200), nullable=False),
            sa.Column("content", sa.Text(), nullable=False),
            sa.Column("mood", sa.String(50), nullable=True),
            sa.Column("weather", sa.String(50), nullable=True),
            sa.Column("is_favorite", sa.Boolean(), server_default="0", nullable=False),
            sa.Column("deleted_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), server_default=sa.func.now(),
                      onupdate=sa.func.now(), nullable=False),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_diary_entry_user_id", "diary_entry", ["user_id"])
        op.create_index("ix_diary_entry_deleted_at", "diary_entry", ["deleted_at"])

    # ---------- 2. 邀请码（情侣关系绑定的凭据） ----------
    if not _has_table("invite_code"):
        op.create_table(
            "invite_code",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("code", sa.String(20), nullable=False),
            sa.Column("is_used", sa.Boolean(), server_default="0", nullable=False),
            sa.Column("used_by", sa.BigInteger(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("used_at", sa.DateTime(), nullable=True),
            sa.Column("expires_at", sa.DateTime(), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), server_default=sa.func.now(),
                      onupdate=sa.func.now(), nullable=False),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_invite_code_user_id", "invite_code", ["user_id"])
        op.create_index("ix_invite_code_code", "invite_code", ["code"], unique=True)

    # ---------- 3. 自我练习定义 + 4. 练习记录（单身模式专属） ----------
    if not _has_table("self_practice"):
        op.create_table(
            "self_practice",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("title", sa.String(200), nullable=False),
            sa.Column("practice_type", sa.String(30), nullable=False),
            sa.Column("description", sa.Text(), nullable=False),
            sa.Column("guidance", sa.Text(), nullable=True),
            sa.Column("is_active", sa.Boolean(), server_default="1", nullable=False),
            sa.PrimaryKeyConstraint("id"),
        )

    if not _has_table("self_practice_record"):
        op.create_table(
            "self_practice_record",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("practice_id", sa.BigInteger(),
                      sa.ForeignKey("self_practice.id"), nullable=False),
            sa.Column("status", sa.String(20), server_default="started", nullable=False),
            sa.Column("content", sa.Text(), nullable=True),
            sa.Column("reflection", sa.Text(), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), server_default=sa.func.now(),
                      onupdate=sa.func.now(), nullable=False),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_self_practice_record_user_id", "self_practice_record", ["user_id"])
        op.create_index("ix_self_practice_record_practice_id", "self_practice_record", ["practice_id"])

    # ---------- 5. user.has_couple（模式判断的依据） ----------
    if not _has_column("user", "has_couple"):
        op.add_column(
            "user",
            sa.Column("has_couple", sa.Boolean(), server_default="0", nullable=False),
        )


def downgrade() -> None:
    if _has_column("user", "has_couple"):
        op.drop_column("user", "has_couple")

    if _has_table("self_practice_record"):
        op.drop_index("ix_self_practice_record_practice_id", table_name="self_practice_record")
        op.drop_index("ix_self_practice_record_user_id", table_name="self_practice_record")
        op.drop_table("self_practice_record")

    if _has_table("self_practice"):
        op.drop_table("self_practice")

    if _has_table("invite_code"):
        op.drop_index("ix_invite_code_code", table_name="invite_code")
        op.drop_index("ix_invite_code_user_id", table_name="invite_code")
        op.drop_table("invite_code")

    if _has_table("diary_entry"):
        op.drop_index("ix_diary_entry_deleted_at", table_name="diary_entry")
        op.drop_index("ix_diary_entry_user_id", table_name="diary_entry")
        op.drop_table("diary_entry")
