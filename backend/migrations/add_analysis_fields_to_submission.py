"""添加 AI 分析报告字段到 questionnaire_submission 表

Revision ID: add_analysis_fields
Revises:
Create Date: 2024-01-01 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa


# revision identifiers
revision = 'add_analysis_fields'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 添加 AI 分析报告结构化字段
    op.add_column('questionnaire_submission', sa.Column('profile_analysis', sa.Text(), nullable=True))
    op.add_column('questionnaire_submission', sa.Column('dimension_analyses', sa.JSON(), nullable=True))
    op.add_column('questionnaire_submission', sa.Column('strengths', sa.Text(), nullable=True))
    op.add_column('questionnaire_submission', sa.Column('growth_tips', sa.JSON(), nullable=True))
    op.add_column('questionnaire_submission', sa.Column('communication_guide', sa.Text(), nullable=True))


def downgrade() -> None:
    # 删除 AI 分析报告结构化字段
    op.drop_column('questionnaire_submission', 'communication_guide')
    op.drop_column('questionnaire_submission', 'growth_tips')
    op.drop_column('questionnaire_submission', 'strengths')
    op.drop_column('questionnaire_submission', 'dimension_analyses')
    op.drop_column('questionnaire_submission', 'profile_analysis')
