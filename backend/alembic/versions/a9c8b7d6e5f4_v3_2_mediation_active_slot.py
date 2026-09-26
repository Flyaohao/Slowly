"""v3.2 整改 B4.2-P0：调解 start 幂等的并发兜底槽位

## 为什么需要这个列

`start_mediation` 此前每次请求都无条件新建会话。网络重试、响应丢失、双击、
双设备可能制造多场活跃邀请。要杜绝「同一情侣关系中同一发起者同时存在多场
活跃调解」，单靠「先 SELECT 再 INSERT」不行——两个并发请求会同时查不到行、
双双插入。

所以加一个**可空的活跃槽位** `mediation_active_slot`：

- 活跃调解时写 ``"<relation_id>:<inviter_id>"``；
- 结束（`completed`）时清空为 NULL；
- 唯一约束 `uk_ai_session_mediation_active` 让「判断」与「插入」压成一条
  原子约束。NULL 在 MySQL / SQLite 的唯一索引里**不参与唯一性判定**，
  因此历史会话与已结束的会话互不冲突。

Revision ID: a9c8b7d6e5f4
Revises: e5f6a7b8c9d0
Create Date: 2026-09-27 01:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'a9c8b7d6e5f4'
down_revision: Union[str, None] = 'e5f6a7b8c9d0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_column(inspector, table: str, column: str) -> bool:
    return column in {c['name'] for c in inspector.get_columns(table)}


def _has_unique(inspector, table: str, name: str) -> bool:
    return name in {uc['name'] for uc in inspector.get_unique_constraints(table)}


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if _has_column(inspector, 'ai_chat_session', 'mediation_active_slot'):
        return
    op.add_column(
        'ai_chat_session',
        sa.Column('mediation_active_slot', sa.String(length=64), nullable=True),
    )
    op.create_unique_constraint(
        'uk_ai_session_mediation_active',
        'ai_chat_session',
        ['mediation_active_slot'],
    )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if _has_unique(inspector, 'ai_chat_session', 'uk_ai_session_mediation_active'):
        op.drop_constraint(
            'uk_ai_session_mediation_active',
            'ai_chat_session',
            type_='unique',
        )
    if _has_column(inspector, 'ai_chat_session', 'mediation_active_slot'):
        op.drop_column('ai_chat_session', 'mediation_active_slot')
