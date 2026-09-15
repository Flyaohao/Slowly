"""v2.1 add ai_generation table

单次触发的分析类 AI（信件解读/改写/回信、表达改写、画像报告）此前结果不落库，
退出页面即丢失。本迁移为它们建立统一的持久化落点。

Revision ID: f4c5d6e7a8b9
Revises: e2f3a4b5c6d7
Create Date: 2026-09-16 00:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f4c5d6e7a8b9'
down_revision: Union[str, None] = 'e2f3a4b5c6d7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：已存在则跳过
    if sa.inspect(op.get_bind()).has_table('ai_generation'):
        return
    op.create_table(
        'ai_generation',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('relation_id', sa.BigInteger(), nullable=False),
        sa.Column('generation_kind', sa.String(50), nullable=False),
        sa.Column('target_type', sa.String(30), server_default='none', nullable=False),
        sa.Column('target_id', sa.BigInteger(), nullable=True),
        sa.Column('scene_key', sa.String(50), nullable=False),
        sa.Column('status', sa.String(20), server_default='streaming', nullable=False),
        # content 不能给 server_default：MySQL 不允许 TEXT 列有默认值。
        # 插入侧一律显式传值，见 ai_generation_repo.upsert_generation。
        sa.Column('content', sa.Text(), nullable=False),
        sa.Column('thinking', sa.Text(), nullable=True),
        sa.Column('structured_output', sa.JSON(), nullable=True),
        sa.Column('risk_level', sa.String(30), nullable=True),
        sa.Column('model', sa.String(50), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['user.id']),
        sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id']),
    )
    op.create_index(
        'ix_ai_generation_lookup',
        'ai_generation',
        ['user_id', 'generation_kind', 'target_type', 'target_id'],
    )


def downgrade() -> None:
    op.drop_index('ix_ai_generation_lookup', table_name='ai_generation')
    op.drop_table('ai_generation')
