"""对齐模型与迁移链的历史偏差

Revision ID: a3b4c5d6e7f8
Revises: f2a3b4c5d6e7
Create Date: 2026-09-14 21:00:00.000000

背景
----
用 scripts/verify_migrations.py 在全新空库上跑 `alembic upgrade head` 后，
与模型定义比对发现三处历史偏差，本迁移一次性对齐：

1. questionnaire_submission 缺 5 个 AI 分析字段
   （profile_analysis / dimension_analyses / strengths / growth_tips / communication_guide）
   —— 这些字段当初是通过 migrations/add_analysis_fields_to_submission.py 这个
   **游离脚本**加的：它的 revision 是自定义字符串、down_revision 为 None，
   且不在 alembic 的 script_location（alembic/versions）里，因此从未进入迁移链。
   本次把它正式收编，随后删除该脚本。

2. letter.relation_id 被建成了 NOT NULL，但模型已声明为 Optional
   —— v2.0 的单身模式信箱没有情侣关系，写入时 relation_id 为 NULL，
   会被 NOT NULL 约束直接拒绝（这正是"单身模式信箱崩溃"的根因之一）。
   这里改为可空，与模型一致。

3. questionnaire_submission 的 ix_submission_user 索引
   —— 由 d5e6f7a8b9c0 建出但模型未声明。改为在模型中显式声明（见
   app/models/questionnaire.py），因此本迁移不做处理。

幂等性说明：MySQL 不支持事务性 DDL，历史库中这些变更可能已被游离脚本应用过，
因此每步都先判断当前状态。
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a3b4c5d6e7f8'
down_revision: Union[str, None] = 'f2a3b4c5d6e7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


SUBMISSION_NEW_COLUMNS = [
    ("profile_analysis", sa.Text()),
    ("dimension_analyses", sa.JSON()),
    ("strengths", sa.Text()),
    ("growth_tips", sa.JSON()),
    ("communication_guide", sa.Text()),
]


def _has_column(table: str, column: str) -> bool:
    insp = sa.inspect(op.get_bind())
    if not insp.has_table(table):
        return False
    return column in {c["name"] for c in insp.get_columns(table)}


def _column_is_nullable(table: str, column: str) -> bool:
    insp = sa.inspect(op.get_bind())
    for c in insp.get_columns(table):
        if c["name"] == column:
            return bool(c["nullable"])
    return True


def upgrade() -> None:
    # ---------- 1. 收编游离脚本加的 AI 分析字段 ----------
    for name, col_type in SUBMISSION_NEW_COLUMNS:
        if not _has_column("questionnaire_submission", name):
            op.add_column("questionnaire_submission", sa.Column(name, col_type, nullable=True))

    # ---------- 2. letter.relation_id 放开为可空（适配单身模式信箱） ----------
    if not _column_is_nullable("letter", "relation_id"):
        op.alter_column(
            "letter",
            "relation_id",
            existing_type=sa.BigInteger(),
            nullable=True,
        )


def downgrade() -> None:
    if _column_is_nullable("letter", "relation_id"):
        op.alter_column(
            "letter",
            "relation_id",
            existing_type=sa.BigInteger(),
            nullable=False,
        )

    for name, _ in SUBMISSION_NEW_COLUMNS:
        if _has_column("questionnaire_submission", name):
            op.drop_column("questionnaire_submission", name)
