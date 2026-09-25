"""v2.9 个人资料补充：出生地

出生地命中本地城市表后，上升星座由「日出 6 点 + 每 2 小时进一宫」的
简化推算升级为地方恒星时精算（见 astrology_service._ascendant_longitude）；
匹配不到/未填则照旧降级，不硬编坐标。同时出生地作为画像辅助信息进入
军师 prompt 与画像驱动的记忆检索关键词。

Revision ID: c0de4b7a1f23
Revises: a7b8c9d0e1f2
Create Date: 2026-09-26 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c0de4b7a1f23'
down_revision: Union[str, None] = 'a7b8c9d0e1f2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('user_profile'):
        return

    columns = {c['name'] for c in inspector.get_columns('user_profile')}
    if 'birth_place' not in columns:
        # 可空：未填/城市未收录 → 上升星座退回简化推算（birthplace_service）。
        op.add_column(
            'user_profile', sa.Column('birth_place', sa.String(length=50), nullable=True)
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('user_profile'):
        return

    columns = {c['name'] for c in inspector.get_columns('user_profile')}
    if 'birth_place' in columns:
        op.drop_column('user_profile', 'birth_place')
