"""v2.10 军师 AI 记忆系统 v3.2 契约落库（实现契约附录 §1）

严格按附录 §1 的 DDL 草案落地，全部幂等（inspector 判表/列/索引/外键）：

1. `couple_relation` 三列治理列（§1.6）：memory_purge_after / memory_purged_at / legal_hold
2. 建表（顺序红线：附录 §1.5 —— memory_pipeline_task 必须先于
   ai_memory.pipeline_task_id 外键）：
   - memory_pipeline_task（§1.5，两级幂等的任务级唯一键在此）
   - memory_assertion_edge（§1.2，断言↔断言四关系）
   - memory_assertion_evidence（§1.3，独立证据数唯一来源）
   - memory_assertion_user_state（§1.4，按用户的 hidden/muted/starred）
3. `ai_memory` 原位升级 ~34 列（§1.1）。**index_status 默认 'skipped'**——
   防止加列后把全部 legacy 行误投递为新索引任务（附录 §1.1 明文要求）；
   v3.2 新写路径必须显式写 pending_upsert。
4. legacy 回填（§8 原则：模型回填不等于可信，回填即打标）：
   schema_version='legacy' / epistemic_type='unknown'（不许默认成 self_report）
   / assertion_origin='legacy' / evidence_lost=TRUE；
   所有权按 visibility 推导。confidence **不回填**（NULL 非声明，§8 封顶只管有值行）。
5. FK ai_memory.pipeline_task_id → memory_pipeline_task.id（必须在 2 之后）
6. 五个索引（含 uk_ai_memory_task_fingerprint 唯一索引，断言级幂等靠它）

⚠️ downgrade 仅用于本地空库；含生产数据的环境**不得**通过 downgrade 删除
assertion 字段或证据表（附录 §6.4：应用回滚只切 feature flags）。

Revision ID: 3f6a1b2c4d8e
Revises: c0de4b7a1f23
Create Date: 2026-09-26 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '3f6a1b2c4d8e'
down_revision: Union[str, None] = 'c0de4b7a1f23'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# ---- ai_memory 新列（§1.1，逐列幂等 ADD 用）-------------------------------
_AI_MEMORY_COLUMNS = [
    sa.Column('schema_version', sa.String(length=20), nullable=True),
    sa.Column('status', sa.String(length=20), nullable=False, server_default=sa.text("'active'")),
    sa.Column('status_reason', sa.String(length=30), nullable=True),
    sa.Column('valid_from', sa.DateTime(), nullable=True),
    sa.Column('valid_until', sa.DateTime(), nullable=True),
    sa.Column('reported_by_user_id', sa.BigInteger(), nullable=True),
    sa.Column('attributed_to_user_id', sa.BigInteger(), nullable=True),
    sa.Column('subject_type', sa.String(length=20), nullable=True),
    sa.Column('subject_user_id', sa.BigInteger(), nullable=True),
    sa.Column('assertion_origin', sa.String(length=30), nullable=True),
    sa.Column('epistemic_type', sa.String(length=30), nullable=True),
    sa.Column('predicate_code', sa.String(length=40), nullable=True),
    sa.Column('object_key', sa.String(length=120), nullable=True),
    sa.Column('fact_key', sa.String(length=255), nullable=True),
    sa.Column('cardinality', sa.String(length=10), nullable=True),
    sa.Column('registry_miss', sa.Boolean(), nullable=False, server_default=sa.text('0')),
    sa.Column('owner_user_id', sa.BigInteger(), nullable=True),
    sa.Column('created_by_user_id', sa.BigInteger(), nullable=True),
    sa.Column('ownership_type', sa.String(length=20), nullable=True),
    sa.Column('user_confirmed', sa.Boolean(), nullable=False, server_default=sa.text('0')),
    sa.Column('confidence', sa.Numeric(4, 3), nullable=True),
    sa.Column('evidence_lost', sa.Boolean(), nullable=False, server_default=sa.text('0')),
    sa.Column('pipeline_task_id', sa.BigInteger(), nullable=True),
    sa.Column('item_fingerprint', sa.String(length=64), nullable=True),
    sa.Column('extractor_model', sa.String(length=80), nullable=True),
    sa.Column('extractor_prompt_version', sa.String(length=40), nullable=True),
    sa.Column('pipeline_version', sa.String(length=40), nullable=True),
    # 默认 'skipped'：legacy 行不得被误投递为索引任务（附录 §1.1 明文）
    sa.Column('index_status', sa.String(length=24), nullable=False, server_default=sa.text("'skipped'")),
    sa.Column('index_generation', sa.Integer(), nullable=False, server_default=sa.text('1')),
    sa.Column('indexed_generation', sa.Integer(), nullable=True),
    sa.Column('embedding_model', sa.String(length=80), nullable=True),
    sa.Column('embedding_version', sa.String(length=40), nullable=True),
    sa.Column('indexed_at', sa.DateTime(), nullable=True),
    sa.Column('index_error', sa.String(length=500), nullable=True),
]


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    # 1. couple_relation 治理列（§1.6）
    if inspector.has_table('couple_relation'):
        columns = {c['name'] for c in inspector.get_columns('couple_relation')}
        if 'memory_purge_after' not in columns:
            op.add_column('couple_relation', sa.Column('memory_purge_after', sa.DateTime(), nullable=True))
        if 'memory_purged_at' not in columns:
            op.add_column('couple_relation', sa.Column('memory_purged_at', sa.DateTime(), nullable=True))
        if 'legal_hold' not in columns:
            op.add_column(
                'couple_relation',
                sa.Column('legal_hold', sa.Boolean(), nullable=False, server_default=sa.text('0')),
            )

    # 2. 四张新表（先 memory_pipeline_task —— §1.5 顺序红线）
    if not inspector.has_table('memory_pipeline_task'):
        op.create_table(
            'memory_pipeline_task',
            sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column('relation_id', sa.BigInteger(), nullable=False),
            sa.Column('requested_by_user_id', sa.BigInteger(), nullable=True),
            sa.Column('trigger_kind', sa.String(length=30), nullable=False),
            sa.Column('source_type', sa.String(length=30), nullable=False),
            sa.Column('source_id', sa.BigInteger(), nullable=False),
            sa.Column('source_revision_id', sa.Integer(), server_default=sa.text('1'), nullable=False),
            sa.Column('pipeline_version', sa.String(length=40), nullable=False),
            sa.Column('idempotency_key', sa.String(length=64), nullable=False),
            sa.Column('state', sa.String(length=40), nullable=False),
            sa.Column('extraction_result', sa.JSON(), nullable=True),
            sa.Column('distill_attempts', sa.SmallInteger(), server_default=sa.text('0'), nullable=False),
            sa.Column('index_attempts', sa.SmallInteger(), server_default=sa.text('0'), nullable=False),
            sa.Column('next_retry_at', sa.DateTime(), nullable=True),
            sa.Column('locked_by', sa.String(length=80), nullable=True),
            sa.Column('locked_until', sa.DateTime(), nullable=True),
            sa.Column('last_error_code', sa.String(length=50), nullable=True),
            sa.Column('last_error_message', sa.String(length=500), nullable=True),
            sa.Column('payload_purge_after', sa.DateTime(), nullable=True),
            sa.Column('created_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.Column('updated_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id'], name='fk_memory_pipeline_relation'),
            sa.PrimaryKeyConstraint('id'),
            sa.UniqueConstraint('idempotency_key', name='uk_memory_pipeline_idempotency'),
        )
        op.create_index('ix_memory_pipeline_claim', 'memory_pipeline_task', ['state', 'next_retry_at', 'locked_until'])
        op.create_index('ix_memory_pipeline_source', 'memory_pipeline_task', ['source_type', 'source_id', 'source_revision_id'])
    # 防御：表已存在但索引缺失（历史手工干预）
    else:
        _create_index_if_missing(
            inspector, 'memory_pipeline_task', 'ix_memory_pipeline_claim',
            ['state', 'next_retry_at', 'locked_until'],
        )
        _create_index_if_missing(
            inspector, 'memory_pipeline_task', 'ix_memory_pipeline_source',
            ['source_type', 'source_id', 'source_revision_id'],
        )

    if not inspector.has_table('memory_assertion_edge'):
        op.create_table(
            'memory_assertion_edge',
            sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column('relation_id', sa.BigInteger(), nullable=False),
            sa.Column('parent_assertion_id', sa.BigInteger(), nullable=False),
            sa.Column('child_assertion_id', sa.BigInteger(), nullable=False),
            sa.Column('relation_type', sa.String(length=20), nullable=False),
            sa.Column('created_by_user_id', sa.BigInteger(), nullable=True),
            sa.Column('created_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.ForeignKeyConstraint(['parent_assertion_id'], ['ai_memory.id'], name='fk_memory_edge_parent'),
            sa.ForeignKeyConstraint(['child_assertion_id'], ['ai_memory.id'], name='fk_memory_edge_child'),
            sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id'], name='fk_memory_edge_relation'),
            sa.PrimaryKeyConstraint('id'),
            sa.UniqueConstraint('parent_assertion_id', 'child_assertion_id', 'relation_type', name='uk_memory_edge'),
        )
        op.create_index('ix_memory_edge_child', 'memory_assertion_edge', ['child_assertion_id', 'relation_type'])
        op.create_index('ix_memory_edge_relation', 'memory_assertion_edge', ['relation_id', 'relation_type'])

    if not inspector.has_table('memory_assertion_evidence'):
        op.create_table(
            'memory_assertion_evidence',
            sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column('assertion_id', sa.BigInteger(), nullable=False),
            sa.Column('source_type', sa.String(length=30), nullable=False),
            sa.Column('source_id', sa.BigInteger(), nullable=False),
            sa.Column('source_revision_id', sa.Integer(), nullable=False),
            sa.Column('offset_codepoint', sa.Integer(), nullable=False),
            sa.Column('length_codepoint', sa.Integer(), nullable=False),
            sa.Column('content_hash', sa.String(length=64), nullable=False),
            sa.Column('evidence_role', sa.String(length=20), server_default=sa.text("'support'"), nullable=False),
            sa.Column('created_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.ForeignKeyConstraint(['assertion_id'], ['ai_memory.id'], name='fk_memory_evidence_assertion'),
            sa.PrimaryKeyConstraint('id'),
            sa.UniqueConstraint(
                'assertion_id', 'source_type', 'source_id', 'source_revision_id',
                'offset_codepoint', 'length_codepoint', 'evidence_role',
                name='uk_memory_evidence',
            ),
        )
        op.create_index(
            'ix_memory_evidence_source', 'memory_assertion_evidence',
            ['source_type', 'source_id', 'source_revision_id'],
        )

    if not inspector.has_table('memory_assertion_user_state'):
        op.create_table(
            'memory_assertion_user_state',
            sa.Column('assertion_id', sa.BigInteger(), nullable=False),
            sa.Column('user_id', sa.BigInteger(), nullable=False),
            sa.Column('hidden', sa.Boolean(), server_default=sa.text('0'), nullable=False),
            sa.Column('muted', sa.Boolean(), server_default=sa.text('0'), nullable=False),
            sa.Column('starred', sa.Boolean(), server_default=sa.text('0'), nullable=False),
            sa.Column('created_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.Column('updated_at', sa.DateTime(), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
            sa.ForeignKeyConstraint(['assertion_id'], ['ai_memory.id'], name='fk_memory_user_state_assertion'),
            sa.ForeignKeyConstraint(['user_id'], ['user.id'], name='fk_memory_user_state_user'),
            sa.PrimaryKeyConstraint('assertion_id', 'user_id'),
        )
        op.create_index('ix_memory_user_state_user', 'memory_assertion_user_state', ['user_id', 'hidden', 'muted'])

    # 3. ai_memory 新列（逐列判存在）
    inspector = sa.inspect(bind)  # 刷新：2 里刚建了表
    if inspector.has_table('ai_memory'):
        columns = {c['name'] for c in inspector.get_columns('ai_memory')}
        for col in _AI_MEMORY_COLUMNS:
            if col.name not in columns:
                op.add_column('ai_memory', col)

        # 4. legacy 回填（§8：回填即打标，永不冒充可信；WHERE 守卫保证幂等）
        bind.execute(sa.text(
            "UPDATE ai_memory SET schema_version = 'legacy', "
            "epistemic_type = 'unknown', assertion_origin = 'legacy', "
            "evidence_lost = TRUE "
            "WHERE schema_version IS NULL"
        ))
        bind.execute(sa.text(
            "UPDATE ai_memory SET owner_user_id = user_id, "
            "created_by_user_id = user_id, "
            "ownership_type = CASE WHEN visibility = 'couple' "
            "THEN 'relation' ELSE 'user' END "
            "WHERE ownership_type IS NULL"
        ))

        # 5. FK 必须在 memory_pipeline_task 建好之后（§1.5 顺序红线）
        fks = {fk['name'] for fk in inspector.get_foreign_keys('ai_memory')}
        if 'fk_ai_memory_pipeline_task' not in fks:
            op.create_foreign_key(
                'fk_ai_memory_pipeline_task',
                'ai_memory', 'memory_pipeline_task',
                ['pipeline_task_id'], ['id'],
            )

        # 6. 五个索引（含断言级幂等唯一键）
        _create_index_if_missing(
            inspector, 'ai_memory', 'ix_ai_memory_recall',
            ['relation_id', 'status', 'visibility', 'occurred_at'],
        )
        _create_index_if_missing(
            inspector, 'ai_memory', 'ix_ai_memory_fact',
            ['relation_id', 'subject_user_id', 'predicate_code', 'object_key', 'status'],
        )
        _create_index_if_missing(
            inspector, 'ai_memory', 'ix_ai_memory_owner',
            ['owner_user_id', 'relation_id', 'status'],
        )
        _create_index_if_missing(
            inspector, 'ai_memory', 'ix_ai_memory_index_work',
            ['index_status', 'index_generation', 'updated_at'],
        )
        _create_index_if_missing(
            inspector, 'ai_memory', 'uk_ai_memory_task_fingerprint',
            ['pipeline_task_id', 'item_fingerprint'],
            unique=True,
        )


def _create_index_if_missing(inspector, table: str, name: str, columns, unique: bool = False) -> None:
    existing = {ix['name'] for ix in inspector.get_indexes(table)}
    if name not in existing:
        op.create_index(name, table, columns, unique=unique)


def downgrade() -> None:
    """仅限本地空库。含生产数据的环境禁止执行（附录 §6.4）。"""
    bind = op.get_bind()
    inspector = sa.inspect(bind)

    # ai_memory：先 FK 后索引后列
    if inspector.has_table('ai_memory'):
        fks = {fk['name'] for fk in inspector.get_foreign_keys('ai_memory')}
        if 'fk_ai_memory_pipeline_task' in fks:
            op.drop_constraint('fk_ai_memory_pipeline_task', 'ai_memory', type_='foreignkey')
        existing = {ix['name'] for ix in inspector.get_indexes('ai_memory')}
        for ix in (
            'ix_ai_memory_recall', 'ix_ai_memory_fact', 'ix_ai_memory_owner',
            'ix_ai_memory_index_work', 'uk_ai_memory_task_fingerprint',
        ):
            if ix in existing:
                op.drop_index(ix, table_name='ai_memory')
        columns = {c['name'] for c in inspector.get_columns('ai_memory')}
        for col in _AI_MEMORY_COLUMNS:
            if col.name in columns:
                op.drop_column('ai_memory', col.name)

    for table in (
        'memory_assertion_user_state', 'memory_assertion_evidence',
        'memory_assertion_edge', 'memory_pipeline_task',
    ):
        if inspector.has_table(table):
            op.drop_table(table)

    if inspector.has_table('couple_relation'):
        columns = {c['name'] for c in inspector.get_columns('couple_relation')}
        for col in ('memory_purge_after', 'memory_purged_at', 'legal_hold'):
            if col in columns:
                op.drop_column('couple_relation', col)
