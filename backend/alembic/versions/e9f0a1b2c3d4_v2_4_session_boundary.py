"""v2.4 session boundary columns on ai_chat_session

P0-10A：会话生命周期四决策点（起/续/止/名）所需列与索引。
全部带默认值 / 可空，保证存量行可平滑迁移；last_message_at 用 created_at 回填。

Revision ID: e9f0a1b2c3d4
Revises: b9c0d1e2f3a4
Create Date: 2026-09-24 14:40:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'e9f0a1b2c3d4'
down_revision: Union[str, None] = 'b9c0d1e2f3a4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    cols = {c['name'] for c in inspector.get_columns('ai_chat_session')}

    # 幂等：逐列补，已存在则跳过（连续 upgrade 两次不报错）
    if 'status' not in cols:
        op.add_column(
            'ai_chat_session',
            sa.Column('status', sa.String(20), server_default='active', nullable=False),
        )
    if 'last_message_at' not in cols:
        op.add_column(
            'ai_chat_session',
            sa.Column('last_message_at', sa.DateTime(), nullable=True),
        )
    if 'message_count' not in cols:
        op.add_column(
            'ai_chat_session',
            sa.Column('message_count', sa.Integer(), server_default='0', nullable=False),
        )
    if 'token_total' not in cols:
        op.add_column(
            'ai_chat_session',
            sa.Column('token_total', sa.Integer(), server_default='0', nullable=False),
        )
    if 'segment_reason' not in cols:
        op.add_column(
            'ai_chat_session',
            sa.Column('segment_reason', sa.String(30), nullable=True),
        )

    # 存量行回填：last_message_at ← created_at
    op.execute(
        "UPDATE ai_chat_session SET last_message_at = created_at "
        "WHERE last_message_at IS NULL"
    )

    idx_names = {i['name'] for i in inspector.get_indexes('ai_chat_session')}
    if 'ix_ai_session_scope' not in idx_names:
        op.create_index(
            'ix_ai_session_scope',
            'ai_chat_session',
            ['user_id', 'relation_id', 'scene_key', 'status', 'last_message_at'],
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    idx_names = {i['name'] for i in inspector.get_indexes('ai_chat_session')}
    if 'ix_ai_session_scope' in idx_names:
        op.drop_index('ix_ai_session_scope', table_name='ai_chat_session')

    cols = {c['name'] for c in inspector.get_columns('ai_chat_session')}
    for col in ('segment_reason', 'token_total', 'message_count', 'last_message_at', 'status'):
        if col in cols:
            op.drop_column('ai_chat_session', col)
