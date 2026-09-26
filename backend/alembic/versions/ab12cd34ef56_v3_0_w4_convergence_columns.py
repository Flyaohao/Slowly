"""v3.0 收敛期 W4 加列（契约 §2.4-1 / §3.3 / §3.4）

三块、全部纯增列（不改不删既有列），仿 d6e7f8a9b0c1 的 inspector 守卫幂等
——线上曾出现过手工加列，重复执行不能炸：

1. ``ai_chat_message.user_id`` —— 消息作者（仅 role="user" 时写入），
   调解「双方已提交」distinct 判定与改写按作者分组依赖它。
   NULL = 旧数据 / assistant 消息。模型侧 P0-2 已落列但从未迁移，
   线上库缺列会直接 SQL 报错。
2. ``ai_avatar`` +4（契约 §3.3 军师设置真列，非 JSON）：
   - ``address_name`` String(50) NULL（NULL/空串 → 对外口径 ""）
   - ``detail_level`` String(10) NOT NULL DEFAULT 'standard'
   - ``proactivity`` String(10) NOT NULL DEFAULT 'moderate'
   - ``show_evidence`` BOOL NOT NULL DEFAULT 1
   NOT NULL 列带 server_default：ADD COLUMN 时存量行自动回填。
3. ``ai_output_feedback`` +2（契约 §3.4 反馈闭环）：
   ``adopted`` BOOL NULL、``outcome`` TEXT NULL ——
   NULL = 尚未回访（/feedback/pending 的筛选条件）。

Revision ID: ab12cd34ef56
Revises: 3f6a1b2c4d8e
Create Date: 2026-09-26 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'ab12cd34ef56'
down_revision: Union[str, None] = '3f6a1b2c4d8e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    # 1. 消息作者（§2.4-1）
    if inspector.has_table('ai_chat_message'):
        columns = {c['name'] for c in inspector.get_columns('ai_chat_message')}
        if 'user_id' not in columns:
            op.add_column(
                'ai_chat_message',
                sa.Column('user_id', sa.BigInteger(), nullable=True),
            )
        # FK 单独守卫（列可能先被手工加过）
        fks = {fk.get('name') for fk in inspector.get_foreign_keys('ai_chat_message')}
        if 'fk_ai_chat_message_user' not in fks:
            op.create_foreign_key(
                'fk_ai_chat_message_user',
                'ai_chat_message',
                'user',
                ['user_id'],
                ['id'],
            )

    # 2. 军师设置 4 列（§3.3）
    if inspector.has_table('ai_avatar'):
        columns = {c['name'] for c in inspector.get_columns('ai_avatar')}
        if 'address_name' not in columns:
            op.add_column(
                'ai_avatar',
                sa.Column('address_name', sa.String(length=50), nullable=True),
            )
        if 'detail_level' not in columns:
            op.add_column(
                'ai_avatar',
                sa.Column(
                    'detail_level',
                    sa.String(length=10),
                    nullable=False,
                    server_default=sa.text("'standard'"),
                ),
            )
        if 'proactivity' not in columns:
            op.add_column(
                'ai_avatar',
                sa.Column(
                    'proactivity',
                    sa.String(length=10),
                    nullable=False,
                    server_default=sa.text("'moderate'"),
                ),
            )
        if 'show_evidence' not in columns:
            op.add_column(
                'ai_avatar',
                sa.Column(
                    'show_evidence',
                    sa.Boolean(),
                    nullable=False,
                    server_default=sa.text('1'),
                ),
            )

    # 3. 反馈闭环 2 列（§3.4）
    if inspector.has_table('ai_output_feedback'):
        columns = {c['name'] for c in inspector.get_columns('ai_output_feedback')}
        if 'adopted' not in columns:
            op.add_column(
                'ai_output_feedback',
                sa.Column('adopted', sa.Boolean(), nullable=True),
            )
        if 'outcome' not in columns:
            op.add_column(
                'ai_output_feedback',
                sa.Column('outcome', sa.Text(), nullable=True),
            )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if inspector.has_table('ai_output_feedback'):
        columns = {c['name'] for c in inspector.get_columns('ai_output_feedback')}
        if 'outcome' in columns:
            op.drop_column('ai_output_feedback', 'outcome')
        if 'adopted' in columns:
            op.drop_column('ai_output_feedback', 'adopted')

    if inspector.has_table('ai_avatar'):
        columns = {c['name'] for c in inspector.get_columns('ai_avatar')}
        if 'show_evidence' in columns:
            op.drop_column('ai_avatar', 'show_evidence')
        if 'proactivity' in columns:
            op.drop_column('ai_avatar', 'proactivity')
        if 'detail_level' in columns:
            op.drop_column('ai_avatar', 'detail_level')
        if 'address_name' in columns:
            op.drop_column('ai_avatar', 'address_name')

    if inspector.has_table('ai_chat_message'):
        # 先摘 FK 再删列（反向创建顺序）
        fks = {fk.get('name') for fk in inspector.get_foreign_keys('ai_chat_message')}
        if 'fk_ai_chat_message_user' in fks:
            op.drop_constraint(
                'fk_ai_chat_message_user', 'ai_chat_message', type_='foreignkey'
            )
        columns = {c['name'] for c in inspector.get_columns('ai_chat_message')}
        if 'user_id' in columns:
            op.drop_column('ai_chat_message', 'user_id')
