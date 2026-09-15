"""v2.0.1 add presence_moment table

Revision ID: d1e2f3a4b5c6
Revises: c9d0e1f2a3b4
Create Date: 2026-09-15 17:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd1e2f3a4b5c6'
down_revision: Union[str, None] = 'c9d0e1f2a3b4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：已存在则跳过
    if sa.inspect(op.get_bind()).has_table('presence_moment'):
        return
    op.create_table(
        'presence_moment',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('relation_id', sa.BigInteger(), sa.ForeignKey('couple_relation.id'), nullable=False),
        sa.Column('user_id', sa.BigInteger(), sa.ForeignKey('user.id'), nullable=False),
        sa.Column('moment_type', sa.String(30), server_default='text', nullable=False),
        sa.Column('content', sa.Text(), nullable=False),
        sa.Column('image_url', sa.String(500), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index('ix_presence_moment_relation_id', 'presence_moment', ['relation_id'])


def downgrade() -> None:
    op.drop_index('ix_presence_moment_relation_id', table_name='presence_moment')
    op.drop_table('presence_moment')
