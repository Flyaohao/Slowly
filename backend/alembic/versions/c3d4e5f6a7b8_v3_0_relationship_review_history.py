"""v3.0 relationship review history

整改契约 §8.7：关系复盘此前借助 `ai_generation`（target_type='none'、
target_id=NULL）落库，而那张表是「同一定位只留最新一条」的覆盖式语义，
于是同一用户永远只有一行——第二次复盘把第一次抹掉，用户回看不到任何历史。
本迁移为复盘建立**追加式**留档表，至少记录事件摘要 / 触发点 / 双方需求 /
建议表达 / 发生时间 / 后续结果，并支持稍后回访任务（recall_at）。

Revision ID: c3d4e5f6a7b8
Revises: 7f3a9c2e5b81
Create Date: 2026-09-26 10:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c3d4e5f6a7b8'
down_revision: Union[str, None] = '7f3a9c2e5b81'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：已存在则跳过
    if sa.inspect(op.get_bind()).has_table('ai_relationship_review'):
        return
    op.create_table(
        'ai_relationship_review',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('relation_id', sa.BigInteger(), nullable=False),
        # 输入侧：用于「重新复盘一次」恢复用户上次写的内容
        sa.Column('description', sa.Text(), nullable=False),
        sa.Column('context', sa.Text(), nullable=True),
        # 契约 §8.7 六项：真实列，不塞 JSON
        #
        # 这里**不能**写 `server_default=''`：MySQL 8.0 明确禁止 TEXT/BLOB/JSON
        # 列带字面量默认值——裸 DDL 报 1101 "can't have a default value"，
        # SQLAlchemy 把它渲染成 `DEFAULT ''` 就必然失败（实测 mysql:8.0 与 9.0.1 同）。
        # 空串由模型层的 `default=""` 在 Python 侧给出，插入语句本来就带值，
        # DDL 上没有默认值不影响任何写入路径。
        sa.Column('summary', sa.Text(), nullable=False),
        sa.Column('trigger', sa.Text(), nullable=False),
        sa.Column('own_need', sa.Text(), nullable=False),
        sa.Column('partner_need', sa.Text(), nullable=False),
        sa.Column('suggested_expression', sa.Text(), nullable=False),
        sa.Column('event_time', sa.DateTime(), nullable=True),
        # 后续结果 + 稍后回访
        sa.Column('outcome', sa.Text(), nullable=True),
        sa.Column('outcome_at', sa.DateTime(), nullable=True),
        sa.Column('recall_at', sa.DateTime(), nullable=True),
        # 生成侧
        sa.Column('status', sa.String(20), server_default='streaming', nullable=False),
        # 同 summary：TEXT 列在 MySQL 上不能有字面量默认值，空串交给模型层 default
        sa.Column('content', sa.Text(), nullable=False),
        sa.Column('structured_output', sa.JSON(), nullable=True),
        sa.Column('risk_level', sa.String(30), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['user.id']),
        sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id']),
    )
    op.create_index(
        'ix_ai_relationship_review_owner',
        'ai_relationship_review',
        ['user_id', 'relation_id', 'id'],
    )
    op.create_index(
        'ix_ai_relationship_review_recall',
        'ai_relationship_review',
        ['user_id', 'recall_at'],
    )


def downgrade() -> None:
    op.drop_index('ix_ai_relationship_review_recall', table_name='ai_relationship_review')
    op.drop_index('ix_ai_relationship_review_owner', table_name='ai_relationship_review')
    op.drop_table('ai_relationship_review')
