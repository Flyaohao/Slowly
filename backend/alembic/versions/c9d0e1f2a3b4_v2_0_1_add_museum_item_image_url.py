"""v2.0.1 add museum_item.image_url

Revision ID: c9d0e1f2a3b4
Revises: b7e8f9a0c1d2
Create Date: 2026-09-15 16:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c9d0e1f2a3b4'
down_revision: Union[str, None] = 'b7e8f9a0c1d2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 幂等：列已存在则跳过
    cols = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("museum_item")}
    if "image_url" in cols:
        return
    op.add_column("museum_item", sa.Column("image_url", sa.String(500), nullable=True))


def downgrade() -> None:
    op.drop_column("museum_item", "image_url")
