"""add_v1_3_tables

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5f6
Create Date: 2026-06-20 20:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b2c3d4e5f6a7'
down_revision: Union[str, None] = 'a1b2c3d4e5f6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('ai_chat_session', sa.Column('session_type', sa.String(length=20), nullable=False, server_default='solo'))
    op.add_column('ai_chat_session', sa.Column('partner_user_id', sa.BigInteger(), nullable=True))
    op.add_column('ai_chat_session', sa.Column('mediation_status', sa.String(length=30), nullable=True))
    op.create_foreign_key('fk_ai_chat_session_partner_user_id', 'ai_chat_session', 'user', ['partner_user_id'], ['id'])

    op.create_table('ai_knowledge_doc',
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('title', sa.String(length=200), nullable=False),
    sa.Column('source', sa.String(length=200), nullable=True),
    sa.Column('content', sa.Text(), nullable=False),
    sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
    sa.Column('created_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )

    op.create_table('ai_knowledge_chunk',
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('doc_id', sa.BigInteger(), nullable=False),
    sa.Column('chunk_text', sa.Text(), nullable=False),
    sa.Column('embedding_id', sa.String(length=100), nullable=True),
    sa.Column('metadata', sa.JSON(), nullable=True),
    sa.ForeignKeyConstraint(['doc_id'], ['ai_knowledge_doc.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('ix_ai_knowledge_chunk_doc_id', 'ai_knowledge_chunk', ['doc_id'])

    op.create_table('ai_memory',
    sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
    sa.Column('user_id', sa.BigInteger(), nullable=False),
    sa.Column('relation_id', sa.BigInteger(), nullable=False),
    sa.Column('memory_type', sa.String(length=30), nullable=False),
    sa.Column('memory_text', sa.Text(), nullable=False),
    sa.Column('visibility', sa.String(length=20), nullable=False, server_default='private'),
    sa.Column('created_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.Column('updated_at', sa.DateTime(), server_default=sa.text('now()'), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['user.id'], ),
    sa.ForeignKeyConstraint(['relation_id'], ['couple_relation.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('ix_ai_memory_user_id', 'ai_memory', ['user_id'])
    op.create_index('ix_ai_memory_relation_id', 'ai_memory', ['relation_id'])


def downgrade() -> None:
    op.drop_index('ix_ai_memory_relation_id', table_name='ai_memory')
    op.drop_index('ix_ai_memory_user_id', table_name='ai_memory')
    op.drop_table('ai_memory')

    op.drop_index('ix_ai_knowledge_chunk_doc_id', table_name='ai_knowledge_chunk')
    op.drop_table('ai_knowledge_chunk')
    op.drop_table('ai_knowledge_doc')

    op.drop_constraint('fk_ai_chat_session_partner_user_id', 'ai_chat_session', type_='foreignkey')
    op.drop_column('ai_chat_session', 'mediation_status')
    op.drop_column('ai_chat_session', 'partner_user_id')
    op.drop_column('ai_chat_session', 'session_type')
