"""v3.4 画像版本溯源：relationship_profile 增加 origin / origin_note / source_viewpoint_id

画像原本只在「重新做问卷」时产生新版本（`version` 递增，维度分挂在 profile_id
下）。本次要给画像接第二个写入方——**用户主动表达的观点**——就必须能回答
「这个版本是怎么来的」，否则历史列表里 20 条版本长得一模一样，撤回等于盲选。

三列的分工：

- `origin`：变更类别（questionnaire / viewpoint_enrich / restore / manual）。
  用 `server_default='questionnaire'` 而不是允许 NULL，是为了让既有行立刻拥有
  正确语义（它们确实全部来自问卷），也让代码不必到处写 `or "questionnaire"`。
- `origin_note`：给人看的说明。
- `source_viewpoint_id`：来源追溯（diary_entry.id）。**刻意不建外键**——
  观点被删除后这个画像版本依然成立，只是来源不能再跳转；建了外键反而会让
  「删一条观点」变成一次跨表级联。

注意：派生版本会沿用源版本的 `questionnaire_id`，因此本迁移**不改**
`questionnaire_id` 的可空性——避免一次非必要的 MODIFY COLUMN。

Revision ID: d2e3f4a5b6c7
Revises: c1d2e3f4a5b6
Create Date: 2026-09-27 15:20:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd2e3f4a5b6c7'
down_revision: Union[str, None] = 'c1d2e3f4a5b6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：逐列判断（加列不像建表可以用 create_table 的 exists 判断）
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("relationship_profile")}

    if "origin" not in cols:
        op.add_column(
            "relationship_profile",
            sa.Column(
                "origin",
                sa.String(length=32),
                nullable=False,
                server_default="questionnaire",
                comment="版本来源：questionnaire / viewpoint_enrich / restore / manual",
            ),
        )
    if "origin_note" not in cols:
        op.add_column(
            "relationship_profile",
            sa.Column("origin_note", sa.String(length=200), nullable=True),
        )
    if "source_viewpoint_id" not in cols:
        op.add_column(
            "relationship_profile",
            sa.Column("source_viewpoint_id", sa.BigInteger(), nullable=True),
        )


def downgrade() -> None:
    insp = sa.inspect(op.get_bind())
    cols = {c["name"] for c in insp.get_columns("relationship_profile")}

    if "source_viewpoint_id" in cols:
        op.drop_column("relationship_profile", "source_viewpoint_id")
    if "origin_note" in cols:
        op.drop_column("relationship_profile", "origin_note")
    if "origin" in cols:
        op.drop_column("relationship_profile", "origin")
