"""普通业务资源及租户隔离。"""

import os
import re

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.execute(
        "CREATE TABLE sample_item (\n id UUID PRIMARY KEY, tenant_id UUID NOT NULL, owner_user_id UUID NOT NULL,\n name VARCHAR(120) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()\n)"
    )
    op.execute(
        "CREATE INDEX sample_item_tenant_owner ON sample_item(tenant_id,owner_user_id,id)"
    )
    op.execute("ALTER TABLE sample_item ENABLE ROW LEVEL SECURITY")
    op.execute("ALTER TABLE sample_item FORCE ROW LEVEL SECURITY")
    op.execute(
        "CREATE POLICY tenant_isolation ON sample_item USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)\n WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)"
    )

    runtime = os.environ["DB_RUNTIME_ROLE"]
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", runtime):
        raise ValueError("DB_RUNTIME_ROLE 不合法")
    op.execute("REVOKE ALL ON sample_item FROM PUBLIC")
    op.execute(f'GRANT USAGE ON SCHEMA public TO "{runtime}"')
    op.execute(f'GRANT SELECT, INSERT ON sample_item TO "{runtime}"')


def downgrade():
    raise RuntimeError("不自动降级；按发布兼容与恢复流程处理")
