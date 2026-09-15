"""v2.0.2 add safety_event table

Revision ID: e2f3a4b5c6d7
Revises: d1e2f3a4b5c6
Create Date: 2026-09-15 17:10:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e2f3a4b5c6d7'
down_revision: Union[str, None] = 'd1e2f3a4b5c6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：已存在则跳过
    if sa.inspect(op.get_bind()).has_table('safety_event'):
        return
    op.create_table(
        'safety_event',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=True),
        sa.Column('scene', sa.String(50), server_default='chat', nullable=False),
        sa.Column('source', sa.String(10), server_default='input', nullable=False),
        sa.Column('risk_level', sa.String(30), nullable=False),
        sa.Column('hit_keywords', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index('ix_safety_event_user_id', 'safety_event', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_safety_event_user_id', table_name='safety_event')
    op.drop_table('safety_event')
