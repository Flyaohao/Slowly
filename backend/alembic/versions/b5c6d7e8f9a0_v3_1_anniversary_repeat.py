"""v3.1 纪念日重复语义（整改契约 §8.8）

契约原文：日期语义修正：区分**一次性**与**每年重复**；禁止「还有 55 天 ·
2025-11-20」年份冲突 → 「每年 11 月 20 日 / 下次 2026-11-20」。

原先 `anniversary` 表只有 `date` 一列，代码默认按年滚动计算，于是「去年的一次性
纪念」会被算成明年的「还有 N 天」，和它旁边印着的年份互相矛盾。这里补一列真实
bool，让「一次性」成为可查询的事实，而不是渲染时猜。

**默认 1（每年重复）**：存量行按既有行为（每年滚动）解释，语义不变不回退。

Revision ID: b5c6d7e8f9a0
Revises: d4e5f6a7b8c9
Create Date: 2026-09-26 22:10:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b5c6d7e8f9a0'
down_revision: Union[str, None] = 'd4e5f6a7b8c9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：列已存在则跳过（本仓库迁移统一这么写，见同目录其他 revision）
    columns = [c["name"] for c in sa.inspect(op.get_bind()).get_columns("anniversary")]
    if "repeat_annually" in columns:
        return
    op.add_column(
        "anniversary",
        sa.Column(
            "repeat_annually",
            sa.Boolean(),
            server_default="1",
            nullable=False,
            comment="true=每年重复；false=一次性（过了不再算下一次）",
        ),
    )


def downgrade() -> None:
    op.drop_column("anniversary", "repeat_annually")
