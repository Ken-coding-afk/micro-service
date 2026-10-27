"""T-11A 样例 revision 0001:核心表 + runtime 授权。

Revision ID: t11a0001
Revises:
Create Date: 2026-10-26
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "t11a0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    """建核心表;账号模型同 flyway 样例(设计 03 §6):owner 持有,runtime 仅 DML。"""
    op.create_table(
        "t11a_agent_note",
        sa.Column("id", sa.BigInteger(), primary_key=True),
        sa.Column("tenant_id", sa.BigInteger(), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
    )
    op.execute(
        "ALTER DEFAULT PRIVILEGES FOR ROLE t11a_alc_owner IN SCHEMA public "
        "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO t11a_alc_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO t11a_alc_runtime"
    )


def downgrade() -> None:
    """设计 10:应用回退不 downgrade 数据库;样例不提供降级路径。"""
    raise NotImplementedError("数据库不做自动 downgrade(设计 10 §3)")
