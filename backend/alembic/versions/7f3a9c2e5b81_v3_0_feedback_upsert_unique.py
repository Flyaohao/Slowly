"""v3.0 整改 §8.3：ai_output_feedback 同用户同消息去重 + 唯一索引

第一轮实现的 ``create_feedback`` 是纯 insert——同一消息反复反馈会堆多行，
旧行 ``outcome IS NULL`` 永久滞留 /feedback/pending，任务卡永远消不掉。
本迁移：

1. 合并历史重复行：按 ``(message_id, user_id)`` 分组，保留 id 最大的行作基底，
   其余行的非空 ``adopted`` / ``outcome`` / ``feedback_tag`` / ``feedback_text``
   / ``rating`` 回填到基底（结果字段绝不因合并丢失）；
2. 建唯一索引 ``uq_ai_output_feedback_msg_user``（inspector 守卫，幂等）。

纯数据合并 + 加索引，不改列结构。

Revision ID: 7f3a9c2e5b81
Revises: ab12cd34ef56
Create Date: 2026-09-26 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '7f3a9c2e5b81'
down_revision: Union[str, None] = 'ab12cd34ef56'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

_INDEX_NAME = 'uq_ai_output_feedback_msg_user'


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('ai_output_feedback'):
        return

    # 1. 合并重复行（同 message_id + user_id）：保留 id 最大行，回填非空字段
    rows = bind.execute(
        sa.text(
            'SELECT id, message_id, user_id, rating, feedback_tag, '
            'feedback_text, adopted, outcome '
            'FROM ai_output_feedback ORDER BY id ASC'
        )
    ).fetchall()

    groups = {}
    for row in rows:
        groups.setdefault((row.message_id, row.user_id), []).append(row)

    for members in groups.values():
        if len(members) < 2:
            continue
        base = members[-1]  # id 升序 → 最后一行是最新行
        # 从旧行回填基底仍为空的结果字段
        merged = {
            'rating': base.rating,
            'feedback_tag': base.feedback_tag,
            'feedback_text': base.feedback_text,
            'adopted': base.adopted,
            'outcome': base.outcome,
        }
        for old in members[:-1]:
            for field in merged:
                if merged[field] is None and getattr(old, field) is not None:
                    merged[field] = getattr(old, field)
        if merged != {
            'rating': base.rating,
            'feedback_tag': base.feedback_tag,
            'feedback_text': base.feedback_text,
            'adopted': base.adopted,
            'outcome': base.outcome,
        }:
            bind.execute(
                sa.text(
                    'UPDATE ai_output_feedback SET rating=:rating, '
                    'feedback_tag=:tag, feedback_text=:text, '
                    'adopted=:adopted, outcome=:outcome WHERE id=:id'
                ),
                {
                    'rating': merged['rating'],
                    'tag': merged['feedback_tag'],
                    'text': merged['feedback_text'],
                    'adopted': merged['adopted'],
                    'outcome': merged['outcome'],
                    'id': base.id,
                },
            )
        stale_ids = [m.id for m in members if m.id != base.id]
        bind.execute(
            sa.text('DELETE FROM ai_output_feedback WHERE id IN :ids').bindparams(
                sa.bindparam('ids', expanding=True)
            ),
            {'ids': stale_ids},
        )

    # 2. 唯一索引（守卫幂等）
    indexes = {ix['name'] for ix in inspector.get_indexes('ai_output_feedback')}
    if _INDEX_NAME not in indexes:
        op.create_index(
            _INDEX_NAME,
            'ai_output_feedback',
            ['message_id', 'user_id'],
            unique=True,
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table('ai_output_feedback'):
        return
    indexes = {ix['name'] for ix in inspector.get_indexes('ai_output_feedback')}
    if _INDEX_NAME in indexes:
        op.drop_index(_INDEX_NAME, table_name='ai_output_feedback')
