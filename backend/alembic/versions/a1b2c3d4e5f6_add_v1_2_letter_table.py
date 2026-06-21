"""add_v1_2_letter_table

Revision ID: a1b2c3d4e5f6
Revises: 91202fedb2f5
Create Date: 2026-06-20 18:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, None] = '91202fedb2f5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table('letter',
    sa.Column('relation_id', sa.BigInteger(), nullable=False),
    sa.Column('sender_id', sa.BigInteger(), nullable=False),
    sa.Column('receiver_id', sa.BigInteger(), nullable=False),
    sa.Column('title', sa.String(length=200), nullable=False),
    sa.Column('content', sa.Text(), nullable=False),
    sa.Column('letter_type', sa.String(length=30), nullable=False),
    sa.Column('status', sa.String(length=20), nullable=False),
    sa.Column('send_time', sa.DateTime(), nullable=True),
    sa.Column('unlock_time', sa.DateTime(), nullable=True),
    sa.Column('is_private', sa.Boolean(), nullable=False),
    sa.Column('is_favorite', sa.Boolean(), nullable=False),
    sa.Column('deleted_at', sa.DateTime(), nullable=True),
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('created_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id'], ),
    sa.ForeignKeyConstraint(['sender_id'], ['user.id'], ),
    sa.ForeignKeyConstraint(['receiver_id'], ['user.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('ix_letter_relation_id', 'letter', ['relation_id'])
    op.create_index('ix_letter_sender_id', 'letter', ['sender_id'])
    op.create_index('ix_letter_receiver_id', 'letter', ['receiver_id'])
    op.create_index('ix_letter_status', 'letter', ['status'])
    op.create_index('ix_letter_letter_type', 'letter', ['letter_type'])


def downgrade() -> None:
    op.drop_index('ix_letter_letter_type', table_name='letter')
    op.drop_index('ix_letter_status', table_name='letter')
    op.drop_index('ix_letter_receiver_id', table_name='letter')
    op.drop_index('ix_letter_sender_id', table_name='letter')
    op.drop_index('ix_letter_relation_id', table_name='letter')
    op.drop_table('letter')
