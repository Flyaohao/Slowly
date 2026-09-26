"""v3.1 mediation confirm state

整改契约 §8.5-5：改写确认必须是**双方各自**的确认状态，只有双方确认后才生成总结。
此前 `confirm(true)` 一个人点就把会话推进 summarizing，对方从没确认过——
「需要修改」也只重生成双方改写（泄露/覆盖对方）。本迁移给会话表加两列，
分别记录发起方与参与方的确认时间（NULL = 未确认）。

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-09-26 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd4e5f6a7b8c9'
down_revision: Union[str, None] = 'c3d4e5f6a7b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    insp = sa.inspect(op.get_bind())
    if not insp.has_table('ai_chat_session'):
        return
    columns = {c['name'] for c in insp.get_columns('ai_chat_session')}
    # 幂等：已存在则跳过（同 d5e6f7a8b9c0 约定）
    if 'confirm_inviter_at' not in columns:
        op.add_column(
            'ai_chat_session',
            sa.Column('confirm_inviter_at', sa.DateTime(), nullable=True),
        )
    if 'confirm_partner_at' not in columns:
        op.add_column(
            'ai_chat_session',
            sa.Column('confirm_partner_at', sa.DateTime(), nullable=True),
        )


def downgrade() -> None:
    insp = sa.inspect(op.get_bind())
    if not insp.has_table('ai_chat_session'):
        return
    columns = {c['name'] for c in insp.get_columns('ai_chat_session')}
    if 'confirm_partner_at' in columns:
        op.drop_column('ai_chat_session', 'confirm_partner_at')
    if 'confirm_inviter_at' in columns:
        op.drop_column('ai_chat_session', 'confirm_inviter_at')
