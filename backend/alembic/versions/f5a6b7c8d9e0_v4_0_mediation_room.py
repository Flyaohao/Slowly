"""v4.0 共同调解室表族

新增：
- `mediation_room`            房间（状态机 + 应答/投票 + 滚动摘要 + 结算租约）
- `room_message`              三方消息（user_a / user_b / advisor）
- `mediation_room_pending`    房间级 pending 生成互斥（room_id 唯一）
- `mediation_event`           结算时结构化落库的吵架事件

变更：
- `diary_entry` 加 `source` 列（NULL=普通观点；'mediation_room'=调解室结算
  压缩产生的观点。D-OPINION：调解室观点默认不计入画像，来源列是筛选落点）。

Revision ID: f5a6b7c8d9e0
Revises: e3f4a5b6c7d8
Create Date: 2026-09-28 03:20:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f5a6b7c8d9e0'
down_revision: Union[str, None] = 'e3f4a5b6c7d8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _tables() -> list:
    return sa.inspect(op.get_bind()).get_table_names()


def upgrade() -> None:
    existing = _tables()

    # 幂等：本仓库迁移统一写法
    if "mediation_room" not in existing:
        op.create_table(
            "mediation_room",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("relation_id", sa.BigInteger(), nullable=False),
            sa.Column("creator_user_id", sa.BigInteger(), nullable=False),
            # 事件卡（§二）
            sa.Column("name", sa.String(length=120), nullable=False),
            sa.Column("event_time", sa.String(length=64), nullable=False),
            sa.Column("cause_text", sa.Text(), nullable=False),
            sa.Column("process_text", sa.Text(), nullable=False),
            sa.Column("current_text", sa.Text(), nullable=False),
            sa.Column("style_key", sa.String(length=40), nullable=False),
            # 状态机（§三）
            sa.Column(
                "status", sa.String(length=20),
                server_default="active", nullable=False,
            ),
            sa.Column(
                "advisor_phase", sa.String(length=20),
                server_default="engaged", nullable=False,
            ),
            sa.Column("round_no", sa.Integer(), server_default="0", nullable=False),
            sa.Column("waiting_reply", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("agree_user_a", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("agree_user_b", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("end_vote_user_a", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("end_vote_user_b", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            # 滚动摘要（§四）
            sa.Column("advisor_summary", sa.Text(), nullable=True),
            sa.Column("summary_upto_message_id", sa.BigInteger(), nullable=True),
            sa.Column("summary_pending", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            # 最近消息水位
            sa.Column("last_message_id", sa.BigInteger(), nullable=True),
            sa.Column("last_message_at", sa.DateTime(), nullable=True),
            # 调解书与结算（D-RESULT）
            sa.Column("settlement", sa.Text(), nullable=True),
            sa.Column("result", sa.String(length=20), nullable=True),
            sa.Column("confirm_user_a", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("confirm_user_b", sa.Boolean(), server_default=sa.text("0"), nullable=False),
            sa.Column("settled_at", sa.DateTime(), nullable=True),
            # 结算租约（worker；不走 ai_task 表，见模型 docstring）
            sa.Column("settle_attempt", sa.SmallInteger(), server_default="0", nullable=False),
            sa.Column("settle_locked_by", sa.String(length=80), nullable=True),
            sa.Column("settle_locked_until", sa.DateTime(), nullable=True),
            sa.Column("settle_next_retry_at", sa.DateTime(), nullable=True),
            sa.Column("settle_last_error", sa.String(length=500), nullable=True),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.Column(
                "updated_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.ForeignKeyConstraint(["relation_id"], ["couple_relation.id"]),
            sa.ForeignKeyConstraint(["creator_user_id"], ["user.id"]),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index(
            "ix_mediation_room_relation_status",
            "mediation_room",
            ["relation_id", "status"],
        )

    if "room_message" not in existing:
        op.create_table(
            "room_message",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("room_id", sa.BigInteger(), nullable=False),
            sa.Column("sender_type", sa.String(length=20), nullable=False),
            sa.Column("sender_user_id", sa.BigInteger(), nullable=True),
            sa.Column("content", sa.Text(), nullable=False),
            sa.Column("thinking", sa.Text(), nullable=True),
            sa.Column("risk_level", sa.String(length=20), nullable=True),
            sa.Column("round_no", sa.Integer(), nullable=True),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.Column(
                "updated_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.ForeignKeyConstraint(["room_id"], ["mediation_room.id"]),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_room_message_room_id", "room_message", ["room_id"])

    if "mediation_room_pending" not in existing:
        op.create_table(
            "mediation_room_pending",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("room_id", sa.BigInteger(), nullable=False),
            sa.Column("kind", sa.String(length=20), server_default="advisor_reply", nullable=False),
            sa.Column("token", sa.String(length=64), nullable=False),
            sa.Column("created_by_user_id", sa.BigInteger(), nullable=False),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.Column(
                "updated_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.ForeignKeyConstraint(["room_id"], ["mediation_room.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint("room_id", name="uk_room_pending_room"),
        )

    if "mediation_event" not in existing:
        op.create_table(
            "mediation_event",
            sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("room_id", sa.BigInteger(), nullable=False),
            sa.Column("relation_id", sa.BigInteger(), nullable=False),
            sa.Column("name", sa.String(length=120), nullable=False),
            sa.Column("event_time", sa.String(length=64), nullable=False),
            sa.Column("cause_text", sa.Text(), nullable=False),
            sa.Column("process_text", sa.Text(), nullable=False),
            sa.Column("result", sa.String(length=20), nullable=False),
            sa.Column("settled_at", sa.DateTime(), nullable=True),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.Column(
                "updated_at", sa.DateTime(), server_default=sa.text("now()"), nullable=False
            ),
            sa.ForeignKeyConstraint(["room_id"], ["mediation_room.id"]),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index("ix_mediation_event_relation", "mediation_event", ["relation_id"])

    # diary_entry.source：调解室观点来源标注（D-OPINION）
    cols = [c["name"] for c in sa.inspect(op.get_bind()).get_columns("diary_entry")]
    if "source" not in cols:
        op.add_column(
            "diary_entry",
            sa.Column("source", sa.String(length=30), nullable=True),
        )


def downgrade() -> None:
    bind = op.get_bind()
    cols = [c["name"] for c in sa.inspect(bind).get_columns("diary_entry")]
    if "source" in cols:
        op.drop_column("diary_entry", "source")
    for name in ("mediation_event", "mediation_room_pending", "room_message", "mediation_room"):
        if name in sa.inspect(bind).get_table_names():
            op.drop_table(name)
