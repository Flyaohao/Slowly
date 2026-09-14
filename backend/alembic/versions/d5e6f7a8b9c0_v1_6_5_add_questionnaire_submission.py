"""v1.6.5 add questionnaire submission table

Revision ID: d5e6f7a8b9c0
Revises: c4d5e6f7a8b9
Create Date: 2026-06-21 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd5e6f7a8b9c0'
down_revision: Union[str, None] = 'c4d5e6f7a8b9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：见 c4d5e6f7a8b9 中的说明。
    if sa.inspect(op.get_bind()).has_table('questionnaire_submission'):
        return
    op.create_table(
        'questionnaire_submission',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('user_id', sa.BigInteger(), sa.ForeignKey('user.id'), nullable=False),
        sa.Column('questionnaire_id', sa.BigInteger(), sa.ForeignKey('questionnaire.id'), nullable=False),
        sa.Column('questionnaire_title', sa.String(200), nullable=False),
        sa.Column('total_questions', sa.Integer(), nullable=False),
        sa.Column('answered_count', sa.Integer(), nullable=False),
        sa.Column('profile_type', sa.String(50), nullable=True),
        sa.Column('profile_summary', sa.Text(), nullable=True),
        sa.Column('dimension_scores', sa.JSON(), nullable=True),
        sa.Column('analysis_text', sa.Text(), nullable=True),
        sa.Column('couple_profile_ready', sa.Boolean(), server_default='0', nullable=False),
        sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index('ix_submission_user', 'questionnaire_submission', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_submission_user', table_name='questionnaire_submission')
    op.drop_table('questionnaire_submission')
