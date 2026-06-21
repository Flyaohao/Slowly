"""create_v1_0_tables

Revision ID: ac1c5929e597
Revises: 
Create Date: 2026-06-20 12:24:41.597303

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'ac1c5929e597'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'user',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('email', sa.String(255), nullable=False),
        sa.Column('password_hash', sa.String(255), nullable=False),
        sa.Column('status', sa.String(20), nullable=False, server_default='active'),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('email'),
    )

    op.create_table(
        'user_profile',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), nullable=False),
        sa.Column('nickname', sa.String(50), nullable=True),
        sa.Column('avatar_url', sa.String(500), nullable=True),
        sa.Column('gender', sa.String(10), nullable=True),
        sa.Column('birthday', sa.Date(), nullable=True),
        sa.Column('city', sa.String(50), nullable=True),
        sa.Column('signature', sa.String(200), nullable=True),
        sa.Column('love_anniversary', sa.Date(), nullable=True),
        sa.Column('private_password_hash', sa.String(255), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['user.id']),
        sa.UniqueConstraint('user_id'),
    )

    op.create_table(
        'couple_relation',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_a_id', sa.BigInteger(), nullable=False),
        sa.Column('user_b_id', sa.BigInteger(), nullable=False),
        sa.Column('status', sa.String(20), nullable=False, server_default='active'),
        sa.Column('bind_time', sa.DateTime(), nullable=True),
        sa.Column('unbind_requested_by', sa.BigInteger(), nullable=True),
        sa.Column('unbind_requested_at', sa.DateTime(), nullable=True),
        sa.Column('unbind_confirmed_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_a_id'], ['user.id']),
        sa.ForeignKeyConstraint(['user_b_id'], ['user.id']),
        sa.ForeignKeyConstraint(['unbind_requested_by'], ['user.id']),
    )
    op.create_index('ix_couple_relation_user_a_id', 'couple_relation', ['user_a_id'])
    op.create_index('ix_couple_relation_user_b_id', 'couple_relation', ['user_b_id'])

    op.create_table(
        'couple_space',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('relation_id', sa.BigInteger(), nullable=False),
        sa.Column('name', sa.String(100), nullable=False, server_default='我们的空间'),
        sa.Column('theme_color', sa.String(20), nullable=False, server_default='#BE185D'),
        sa.Column('background_url', sa.String(500), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id']),
        sa.UniqueConstraint('relation_id'),
    )


def downgrade() -> None:
    op.drop_table('couple_space')
    op.drop_index('ix_couple_relation_user_b_id', table_name='couple_relation')
    op.drop_index('ix_couple_relation_user_a_id', table_name='couple_relation')
    op.drop_table('couple_relation')
    op.drop_table('user_profile')
    op.drop_table('user')
