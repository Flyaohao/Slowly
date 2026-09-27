"""v3.5 记忆治理：couple_relation 增加 memory_distill_enabled（军师记忆沉淀总开关）

记忆系统升级评估（2026-09-27）P0④：军师记忆此前没有「生成对话记忆」的
服务端总开关（只有测试用环境变量 COUPLE_DISABLE_MEMORY_DISTILL，非产品能力）。
本迁移给关系加一列布尔开关，服务端在 distill 入口读它阻断，任一成员可改。

- 关系级而非用户级：军师是双人共同资产，记忆也按 relation 隔离召回，
  单方关掉 = 这段关系停止沉淀，语义最干净；
- `server_default='1'`：既有关系立刻拥有正确语义（默认开，行为不变）；
- 观点「计入军师记忆」的用户直写不受它影响（见模型列注释）。

Revision ID: e3f4a5b6c7d8
Revises: d2e3f4a5b6c7
Create Date: 2026-09-27 19:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e3f4a5b6c7d8'
down_revision: Union[str, None] = 'd2e3f4a5b6c7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("couple_relation")}

    if "memory_distill_enabled" not in cols:
        op.add_column(
            "couple_relation",
            sa.Column(
                "memory_distill_enabled",
                sa.Boolean(),
                nullable=False,
                server_default=sa.text("1"),
                comment="军师记忆沉淀总开关（关系级）：0=distill 入口阻断",
            ),
        )


def downgrade() -> None:
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("couple_relation")}

    if "memory_distill_enabled" in cols:
        op.drop_column("couple_relation", "memory_distill_enabled")
