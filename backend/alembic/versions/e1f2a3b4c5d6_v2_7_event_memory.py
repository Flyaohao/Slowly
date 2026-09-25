"""v2.7 事件记忆地基（P-C1 §1）

设计文档 §9 / §2.4.1 的结构一次性落地（v2_6 按边界未包含，用新 revision 承载）：

1. `ai_memory` 四列（全部幂等 ADD，inspector 判列集）：
   - `occurred_at`  DATETIME NULL   —— 事件发生时间；回填 = created_at
   - `source`       VARCHAR(30) NULL —— 来源（letter/diary/dual/anniversary/…）
   - `source_id`    BIGINT NULL      —— 源实体 id
   - `importance`   TINYINT NOT NULL server_default 0 —— 0 常规 / 1 高价值 / 2 标星（P-C3）
2. 索引 `ix_ai_memory_relation_occurred (relation_id, occurred_at)` —— 时间衰减排序用，
   用 `inspector.get_indexes` 判存在。
3. `diary_entry.relation_id BIGINT NULL` —— 单身日记不写记忆，取到 active relation 才写。

⚠️ `d6e7f8a9b0c1_v2_6_chat_mode.py` 已进 b97bf6b，不许改写；
   本文件 down_revision 必须是 d6e7f8a9b0c1。

Revision ID: e1f2a3b4c5d6
Revises: d6e7f8a9b0c1
Create Date: 2026-09-25 15:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e1f2a3b4c5d6'
down_revision: Union[str, None] = 'd6e7f8a9b0c1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    # 1. ai_memory 四列
    if inspector.has_table('ai_memory'):
        columns = {c['name'] for c in inspector.get_columns('ai_memory')}
        if 'occurred_at' not in columns:
            op.add_column(
                'ai_memory',
                sa.Column('occurred_at', sa.DateTime(), nullable=True),
            )
        if 'source' not in columns:
            op.add_column(
                'ai_memory',
                sa.Column('source', sa.String(length=30), nullable=True),
            )
        if 'source_id' not in columns:
            op.add_column(
                'ai_memory',
                sa.Column('source_id', sa.BigInteger(), nullable=True),
            )
        if 'importance' not in columns:
            op.add_column(
                'ai_memory',
                sa.Column(
                    'importance',
                    sa.SmallInteger(),
                    nullable=False,
                    server_default=sa.text('0'),
                ),
            )
        # 回填：occurred_at 为空的历史记忆以入库时间为发生时间。
        # WHERE occurred_at IS NULL → 幂等，重复执行不覆盖已有值。
        bind.execute(sa.text(
            'UPDATE ai_memory SET occurred_at = created_at WHERE occurred_at IS NULL'
        ))

        # 2. 时间衰减排序索引（判存在——线上出过手工加列/加索引的先例）
        existing_indexes = {ix['name'] for ix in inspector.get_indexes('ai_memory')}
        if 'ix_ai_memory_relation_occurred' not in existing_indexes:
            op.create_index(
                'ix_ai_memory_relation_occurred',
                'ai_memory',
                ['relation_id', 'occurred_at'],
            )

    # 3. diary_entry.relation_id（单身日记边界：取到 active relation 才写记忆）
    if inspector.has_table('diary_entry'):
        columns = {c['name'] for c in inspector.get_columns('diary_entry')}
        if 'relation_id' not in columns:
            op.add_column(
                'diary_entry',
                sa.Column('relation_id', sa.BigInteger(), nullable=True),
            )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if inspector.has_table('diary_entry'):
        columns = {c['name'] for c in inspector.get_columns('diary_entry')}
        if 'relation_id' in columns:
            op.drop_column('diary_entry', 'relation_id')

    if inspector.has_table('ai_memory'):
        existing_indexes = {ix['name'] for ix in inspector.get_indexes('ai_memory')}
        if 'ix_ai_memory_relation_occurred' in existing_indexes:
            op.drop_index('ix_ai_memory_relation_occurred', table_name='ai_memory')
        columns = {c['name'] for c in inspector.get_columns('ai_memory')}
        for col in ('occurred_at', 'source', 'source_id', 'importance'):
            if col in columns:
                op.drop_column('ai_memory', col)
