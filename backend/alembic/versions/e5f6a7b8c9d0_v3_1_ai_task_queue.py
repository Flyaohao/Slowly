"""v3.1 整改 B4.1：持久化 AI 任务表 + 调解会话失败/版本列

## 这张表取代了什么

调解的改写与总结此前跑在 `threading.Thread(daemon=True)` 里（见旧
`mediation_service._spawn_rewrite`）。守护线程在进程重启 / worker 回收 /
容器发布时会被直接掐掉，而会话状态已经翻成了 `rewriting` / `summarizing`：
用户端表现为**永久停在「AI 正在生成」**，服务端也不再有东西能推进它。
`ai_task` 让任务先落库再执行，执行者崩了由租约超时回收重排。

## 会话侧新增列

- `mediation_revision`：会话内容版本。每次改写被重新生成就 +1，任务只写回
  版本仍等于自己那一版的结果——**旧任务不得覆盖新版本结果**。
- `mediation_failure_code` / `mediation_last_error` / `mediation_failed_at`：
  重试耗尽后的终态失败必须能被 API 区分出来，而不是伪装成一直 processing。

Revision ID: e5f6a7b8c9d0
Revises: b5c6d7e8f9a0
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'e5f6a7b8c9d0'
down_revision: Union[str, None] = 'b5c6d7e8f9a0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(inspector, name: str) -> bool:
    return name in inspector.get_table_names()


def _has_column(inspector, table: str, column: str) -> bool:
    return column in {c['name'] for c in inspector.get_columns(table)}


def _fk_names(inspector, table: str) -> list:
    """表上的外键约束名（按实际名字取，不假设命名规则）。

    为什么必须查而不是写死：MySQL 会为没有可用索引的外键**自动建索引**，
    而 `ix_ai_task_session` 的最左列正是 `session_id`，于是它成了 FK 的支撑
    索引。此时删索引会报 1553「Cannot drop index … needed in a foreign key
    constraint」——本迁移的 downgrade 在 MySQL 9.0.1 上实测踩中（见
    `tests/verify_migration_scratch.py`）。所以先摘外键、再删索引。
    """
    return [fk['name'] for fk in inspector.get_foreign_keys(table) if fk.get('name')]


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if not _has_table(inspector, 'ai_task'):
        op.create_table(
            'ai_task',
            sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column('task_type', sa.String(length=40), nullable=False),
            sa.Column('session_id', sa.BigInteger(), nullable=False),
            sa.Column('requested_by_user_id', sa.BigInteger(), nullable=True),
            sa.Column('revision', sa.Integer(), nullable=False, server_default='0'),
            sa.Column('idempotency_key', sa.String(length=64), nullable=False),
            sa.Column('state', sa.String(length=20), nullable=False),
            sa.Column('attempt', sa.SmallInteger(), nullable=False, server_default='0'),
            sa.Column('max_attempts', sa.SmallInteger(), nullable=False, server_default='3'),
            sa.Column('next_retry_at', sa.DateTime(), nullable=True),
            sa.Column('last_error', sa.String(length=500), nullable=True),
            sa.Column('payload', sa.JSON(), nullable=True),
            sa.Column('started_at', sa.DateTime(), nullable=True),
            sa.Column('finished_at', sa.DateTime(), nullable=True),
            sa.Column('heartbeat_at', sa.DateTime(), nullable=True),
            sa.Column('locked_by', sa.String(length=80), nullable=True),
            sa.Column('locked_until', sa.DateTime(), nullable=True),
            sa.Column('created_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
            sa.Column('updated_at', sa.DateTime(), server_default=sa.func.now(), nullable=False),
            sa.ForeignKeyConstraint(['session_id'], ['ai_chat_session.id']),
            sa.ForeignKeyConstraint(['requested_by_user_id'], ['user.id']),
            sa.PrimaryKeyConstraint('id'),
            sa.UniqueConstraint('idempotency_key', name='uk_ai_task_idempotency'),
        )
        op.create_index(
            'ix_ai_task_claim', 'ai_task',
            ['state', 'next_retry_at', 'locked_until'],
        )
        op.create_index(
            'ix_ai_task_session', 'ai_task',
            ['session_id', 'task_type', 'revision'],
        )
        inspector = sa.inspect(bind)

    if _has_table(inspector, 'ai_chat_session'):
        columns = [
            ('mediation_revision', sa.Column(
                'mediation_revision', sa.Integer(), nullable=False, server_default='0')),
            ('mediation_failure_code', sa.Column(
                'mediation_failure_code', sa.String(length=40), nullable=True)),
            ('mediation_last_error', sa.Column(
                'mediation_last_error', sa.String(length=500), nullable=True)),
            ('mediation_failed_at', sa.Column(
                'mediation_failed_at', sa.DateTime(), nullable=True)),
            ('rewrite_task_id', sa.Column('rewrite_task_id', sa.BigInteger(), nullable=True)),
            ('summary_task_id', sa.Column('summary_task_id', sa.BigInteger(), nullable=True)),
        ]
        for name, column in columns:
            if not _has_column(inspector, 'ai_chat_session', name):
                op.add_column('ai_chat_session', column)


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    if _has_table(inspector, 'ai_chat_session'):
        for name in (
            'summary_task_id', 'rewrite_task_id',
            'mediation_failed_at', 'mediation_last_error',
            'mediation_failure_code', 'mediation_revision',
        ):
            if _has_column(inspector, 'ai_chat_session', name):
                op.drop_column('ai_chat_session', name)

    if _has_table(inspector, 'ai_task'):
        inspector = sa.inspect(bind)
        # 先摘外键：`ix_ai_task_session` 的最左列 session_id 会被 MySQL 用作
        # FK 的支撑索引，不摘外键就删索引会报 1553。外键名由 MySQL 自动生成，
        # 所以按实际名字取（不假设 `ai_task_ibfk_1` 这个命名）。
        for fk_name in _fk_names(inspector, 'ai_task'):
            op.drop_constraint(fk_name, 'ai_task', type_='foreignkey')
        inspector = sa.inspect(bind)
        indexes = {ix['name'] for ix in inspector.get_indexes('ai_task')}
        for name in ('ix_ai_task_session', 'ix_ai_task_claim'):
            if name in indexes:
                op.drop_index(name, table_name='ai_task')
        op.drop_table('ai_task')
