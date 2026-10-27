CREATE TABLE sample_item (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, owner_user_id UUID NOT NULL,
 name VARCHAR(120) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sample_item_tenant_owner ON sample_item(tenant_id,owner_user_id,id);
ALTER TABLE sample_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE sample_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sample_item USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);

REVOKE ALL ON sample_item FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO "${runtime_role}";
GRANT SELECT, INSERT ON sample_item TO "${runtime_role}";
