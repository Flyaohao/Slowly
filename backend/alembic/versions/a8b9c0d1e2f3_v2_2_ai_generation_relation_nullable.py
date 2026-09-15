"""v2.2 ai_generation.relation_id nullable

画像报告这类个人维度的 AI 生成没有「关系」上下文，但 relation_id 是非空外键，
导致单次触发型流式基建（ai_generation）无法承接个人维度的生成。
本迁移把该列放宽为可空：有关系的生成照旧写 relation_id，个人维度的写 NULL。

Revision ID: a8b9c0d1e2f3
Revises: f4c5d6e7a8b9
Create Date: 2026-09-16 02:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a8b9c0d1e2f3'
down_revision: Union[str, None] = 'f4c5d6e7a8b9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：表不存在或列已是可空则跳过
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table('ai_generation'):
        return
    columns = {c['name']: c for c in inspector.get_columns('ai_generation')}
    if 'relation_id' not in columns or columns['relation_id'].get('nullable'):
        return
    # FK 保持不变，只放宽 NOT NULL（MySQL 走 MODIFY COLUMN）
    op.alter_column(
        'ai_generation',
        'relation_id',
        existing_type=sa.BigInteger(),
        nullable=True,
    )


def downgrade() -> None:
    # 回滚要求表中没有 relation_id 为 NULL 的行
    op.alter_column(
        'ai_generation',
        'relation_id',
        existing_type=sa.BigInteger(),
        nullable=False,
    )
