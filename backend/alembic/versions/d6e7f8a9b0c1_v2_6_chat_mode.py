"""v2.6 三档对话模式与语气来源

P-B 只加两列，其余全部走代码：

1. `ai_chat_message.chat_mode` —— 这条消息由哪一档生成
   （quick / deep / expert；NULL = 旧数据 / 未标注）。
   只落库，不进 MessageOut——DTO 契约测试是静态正则扫描，等 P-C 一起放开。
2. `ai_avatar.voice_style_source` —— 语气是画像自动选的（auto）还是
   用户手选的（manual）。手选一次即写 manual；画像驱动的自动重算是 P-D。

**不包含** diary_entry.relation_id（§11 明确不做）。

幂等：仿 b9c0d1e2f3a4，用 inspector 检查列集，已存在则跳过——
线上曾出现过手工加列，重复执行不能炸。

Revision ID: d6e7f8a9b0c1
Revises: e9f0a1b2c3d4
Create Date: 2026-09-25 10:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd6e7f8a9b0c1'
down_revision: Union[str, None] = 'e9f0a1b2c3d4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    # 1. 消息档位标注
    if inspector.has_table('ai_chat_message'):
        columns = {c['name'] for c in inspector.get_columns('ai_chat_message')}
        if 'chat_mode' not in columns:
            op.add_column(
                'ai_chat_message',
                sa.Column('chat_mode', sa.String(length=10), nullable=True),
            )

    # 2. 语气来源
    if inspector.has_table('ai_avatar'):
        columns = {c['name'] for c in inspector.get_columns('ai_avatar')}
        if 'voice_style_source' not in columns:
            op.add_column(
                'ai_avatar',
                sa.Column(
                    'voice_style_source',
                    sa.String(length=10),
                    nullable=False,
                    server_default=sa.text("'auto'"),
                ),
            )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if inspector.has_table('ai_chat_message'):
        columns = {c['name'] for c in inspector.get_columns('ai_chat_message')}
        if 'chat_mode' in columns:
            op.drop_column('ai_chat_message', 'chat_mode')

    if inspector.has_table('ai_avatar'):
        columns = {c['name'] for c in inspector.get_columns('ai_avatar')}
        if 'voice_style_source' in columns:
            op.drop_column('ai_avatar', 'voice_style_source')
