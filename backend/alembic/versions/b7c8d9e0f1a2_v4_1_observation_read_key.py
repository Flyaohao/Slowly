"""v4.1 军师观察卡：couple_relation 增加 observation_read_key（已读位）

《军师主动观察》设计文档（2026-09-28）§七 决策⑥：观察卡已读走服务端
ack（角标跨设备）。V1 聚合版观察由既有数据拼装、无生成物，已读位存
「当前观察内容的签名」；V2 事件驱动生成时升级为 last_read_observation_id。

- 可空列：老数据视为「从未读过」，首次打开观察卡即 ack；
- 无 server_default：业务语义是「未知」，不该给既有关系伪造一个已读签名。

Revision ID: b7c8d9e0f1a2
Revises: f5a6b7c8d9e0
Create Date: 2026-09-28 05:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b7c8d9e0f1a2'
down_revision: Union[str, None] = 'f5a6b7c8d9e0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("couple_relation")}

    if "observation_read_key" not in cols:
        op.add_column(
            "couple_relation",
            sa.Column(
                "observation_read_key",
                sa.String(length=64),
                nullable=True,
                comment="军师观察卡已读位：最近一次 ack 的观察内容签名",
            ),
        )


def downgrade() -> None:
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("couple_relation")}

    if "observation_read_key" in cols:
        op.drop_column("couple_relation", "observation_read_key")
