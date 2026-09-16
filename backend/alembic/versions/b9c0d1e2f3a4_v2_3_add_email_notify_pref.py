"""v2.3 邮件通知开关与发送台账

主线 C（通知双通道）方案②的必要存储：
1. `user_profile.email_notify_enabled` —— 设置页那个「邮件通知」开关，默认关闭；
2. `notification_email_log` —— 发信台账，用于同类事件冷却与每日上限。

两者放同一个迁移，因为它们属于同一个功能，拆开会在线上留下
「开关能开但限流没落地」的中间态。

Revision ID: b9c0d1e2f3a4
Revises: a8b9c0d1e2f3
Create Date: 2026-09-16 15:10:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b9c0d1e2f3a4'
down_revision: Union[str, None] = 'a8b9c0d1e2f3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()

    # 1. 开关字段（幂等：列已存在则跳过）
    inspector = sa.inspect(bind)
    if inspector.has_table('user_profile'):
        columns = {c['name'] for c in inspector.get_columns('user_profile')}
        if 'email_notify_enabled' not in columns:
            op.add_column(
                'user_profile',
                sa.Column(
                    'email_notify_enabled',
                    sa.Boolean(),
                    nullable=False,
                    server_default=sa.text('0'),
                ),
            )

    # 2. 发信台账（幂等：表已存在则跳过）
    inspector = sa.inspect(bind)
    if not inspector.has_table('notification_email_log'):
        op.create_table(
            'notification_email_log',
            sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column('user_id', sa.BigInteger(), nullable=False),
            sa.Column('event_type', sa.String(length=40), nullable=False),
            sa.Column(
                'created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False
            ),
            sa.Column(
                'updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False
            ),
            sa.ForeignKeyConstraint(['user_id'], ['user.id']),
            sa.PrimaryKeyConstraint('id'),
        )
        op.create_index(
            'ix_notif_email_user_type',
            'notification_email_log',
            ['user_id', 'event_type', 'created_at'],
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if inspector.has_table('notification_email_log'):
        op.drop_index('ix_notif_email_user_type', table_name='notification_email_log')
        op.drop_table('notification_email_log')

    inspector = sa.inspect(bind)
    if inspector.has_table('user_profile'):
        columns = {c['name'] for c in inspector.get_columns('user_profile')}
        if 'email_notify_enabled' in columns:
            op.drop_column('user_profile', 'email_notify_enabled')
