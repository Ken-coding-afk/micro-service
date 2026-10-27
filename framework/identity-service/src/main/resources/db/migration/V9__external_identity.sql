-- 外部身份仅显式绑定已有租户账号；不记录提供方 token、邮箱或用户资料。
CREATE TABLE sys_external_identity (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    provider_registration_id varchar(64) NOT NULL,
    provider_authority varchar(512) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,provider_registration_id,provider_subject),
    UNIQUE(tenant_id,user_id,provider_registration_id),
    FOREIGN KEY(tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
);
ALTER TABLE sys_external_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_external_identity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_external_identity
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON sys_external_identity FROM PUBLIC;
GRANT SELECT,INSERT,DELETE ON sys_external_identity TO "${runtime_role}";
