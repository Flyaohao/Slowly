"""v2.8 个人资料补充：出生时辰 + MBTI

用户拍板的画像补强（详见 astrology_service 模块 docstring）：
1. 生日 → 星座、出生时辰 → 简易星盘：两样都在服务端由既有 birthday 派生，
   只有「时辰」是新列；
2. MBTI 16 型由用户自选（下拉框），落成独立列，便于日后单独评估其
   相对问卷画像的专业性权重；
3. 三者与问卷画像并列注入军师 prompt。

Revision ID: a7b8c9d0e1f2
Revises: e1f2a3b4c5d6
Create Date: 2026-09-26 10:20:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a7b8c9d0e1f2'
down_revision: Union[str, None] = 'e1f2a3b4c5d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('user_profile'):
        return

    columns = {c['name'] for c in inspector.get_columns('user_profile')}
    if 'birth_hour' not in columns:
        # 可空：老用户没有时辰 → 星盘只出太阳/月亮，不编上升。
        op.add_column('user_profile', sa.Column('birth_hour', sa.SmallInteger(), nullable=True))
    if 'mbti' not in columns:
        # 可空：未填即不注入 MBTI 段，不塞默认型。
        op.add_column('user_profile', sa.Column('mbti', sa.String(length=8), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('user_profile'):
        return

    columns = {c['name'] for c in inspector.get_columns('user_profile')}
    if 'mbti' in columns:
        op.drop_column('user_profile', 'mbti')
    if 'birth_hour' in columns:
        op.drop_column('user_profile', 'birth_hour')
