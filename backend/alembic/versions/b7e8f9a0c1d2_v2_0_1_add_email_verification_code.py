"""v2.0.1 add email_verification_code table

Revision ID: b7e8f9a0c1d2
Revises: a3b4c5d6e7f8
Create Date: 2026-09-15 15:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b7e8f9a0c1d2'
down_revision: Union[str, None] = 'a3b4c5d6e7f8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：已存在则跳过（同 d5e6f7a8b9c0 约定）
    if sa.inspect(op.get_bind()).has_table('email_verification_code'):
        return
    op.create_table(
        'email_verification_code',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('email', sa.String(255), nullable=False),
        sa.Column('code', sa.String(10), nullable=False),
        sa.Column('purpose', sa.String(30), server_default='reset_password', nullable=False),
        sa.Column('expires_at', sa.DateTime(), nullable=False),
        sa.Column('used_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index('ix_email_verification_code_email', 'email_verification_code', ['email'])


def downgrade() -> None:
    op.drop_index('ix_email_verification_code_email', table_name='email_verification_code')
    op.drop_table('email_verification_code')
