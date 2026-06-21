"""v1.5_add_avatar_presence_fields

Revision ID: f1a2b3c4d5e6
Revises: e85adae883dc
Create Date: 2026-06-20 20:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f1a2b3c4d5e6'
down_revision: Union[str, None] = 'e85adae883dc'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ai_avatar 表
    op.create_table('ai_avatar',
    sa.Column('relation_id', sa.BigInteger(), nullable=False),
    sa.Column('name', sa.String(length=50), nullable=False),
    sa.Column('body_color', sa.String(length=20), nullable=False),
    sa.Column('face_config', sa.JSON(), nullable=True),
    sa.Column('outfit_config', sa.JSON(), nullable=True),
    sa.Column('voice_style', sa.String(length=30), nullable=False),
    sa.Column('background_url', sa.String(length=500), nullable=True),
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('created_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('relation_id')
    )
    with op.batch_alter_table('ai_avatar', schema=None) as batch_op:
        batch_op.create_index('ix_ai_avatar_relation_id', ['relation_id'], unique=False)

    # ai_avatar_asset 表
    op.create_table('ai_avatar_asset',
    sa.Column('asset_type', sa.String(length=30), nullable=False),
    sa.Column('asset_url', sa.String(length=500), nullable=False),
    sa.Column('unlock_condition', sa.String(length=200), nullable=True),
    sa.Column('created_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.PrimaryKeyConstraint('id')
    )

    # couple_space 扩展字段
    with op.batch_alter_table('couple_space', schema=None) as batch_op:
        batch_op.add_column(sa.Column('next_meet_date', sa.Date(), nullable=True))
        batch_op.add_column(sa.Column('presence_enabled', sa.Boolean(), nullable=False, server_default=sa.text('true')))


def downgrade() -> None:
    with op.batch_alter_table('couple_space', schema=None) as batch_op:
        batch_op.drop_column('presence_enabled')
        batch_op.drop_column('next_meet_date')

    op.drop_table('ai_avatar_asset')

    with op.batch_alter_table('ai_avatar', schema=None) as batch_op:
        batch_op.drop_index('ix_ai_avatar_relation_id')

    op.drop_table('ai_avatar')
