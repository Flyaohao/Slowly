"""v3.3 关系历史事件表（用户手动记录，带写入门槛）

新增 `relationship_event`：记录「哪天什么时候发生了什么」，与 `anniversary`
的区别是——纪念日是可重复的日期锚点，本表是一次性发生过的具体事件。

`reason` 与 `polarity` 设为 NOT NULL 是**产品约束的物理落点**：事件只有在
写清「对这段关系起了什么作用」之后才允许落库，否则一条孤立记录会被 AI 当成
关系状态的证据（把偶发当模式）。校验在 service 层，这里保证数据库也不会
接受空值。

Revision ID: c1d2e3f4a5b6
Revises: a9c8b7d6e5f4
Create Date: 2026-09-27 14:40:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c1d2e3f4a5b6'
down_revision: Union[str, None] = 'a9c8b7d6e5f4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：表已存在则跳过（本仓库迁移统一这么写）
    if "relationship_event" in sa.inspect(op.get_bind()).get_table_names():
        return
    op.create_table(
        "relationship_event",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("relation_id", sa.BigInteger(), nullable=False),
        sa.Column("title", sa.String(length=200), nullable=False),
        sa.Column("event_time", sa.DateTime(), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column(
            "reason",
            sa.Text(),
            nullable=False,
            comment="写入原因：这件事对情侣关系起到的明确作用",
        ),
        sa.Column(
            "polarity",
            sa.String(length=16),
            nullable=False,
            comment="作用性质：positive=积极 / negative=消极",
        ),
        sa.Column("created_by_user_id", sa.BigInteger(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["relation_id"], ["couple_relation.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_relationship_event_relation_id", "relationship_event", ["relation_id"]
    )
    op.create_index(
        "ix_relationship_event_time", "relationship_event", ["event_time"]
    )


def downgrade() -> None:
    op.drop_index("ix_relationship_event_time", table_name="relationship_event")
    op.drop_index(
        "ix_relationship_event_relation_id", table_name="relationship_event"
    )
    op.drop_table("relationship_event")
