"""T-11A 样例 revision 0002:兼容扩展(可空列 + 索引,旧列保留)。

Revision ID: t11a0002
Revises: t11a0001
Create Date: 2026-10-26
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "t11a0002"
down_revision = "t11a0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "t11a_agent_note",
        sa.Column("pinned", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.create_index("idx_t11a_agent_note_tenant", "t11a_agent_note", ["tenant_id"])


def downgrade() -> None:
    """设计 10:应用回退不 downgrade 数据库;样例不提供降级路径。"""
    raise NotImplementedError("数据库不做自动 downgrade(设计 10 §3)")
