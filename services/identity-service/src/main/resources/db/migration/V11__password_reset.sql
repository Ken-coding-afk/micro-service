-- 一次性重置凭证只保存摘要；控制表由身份用例显式验证目标，不能作为业务数据入口。
CREATE TABLE identity_technical.password_reset (
    token_hash varchar(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    auth_version bigint NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
);
CREATE INDEX password_reset_user ON identity_technical.password_reset(tenant_id,user_id);
REVOKE ALL ON identity_technical.password_reset FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON identity_technical.password_reset TO "${runtime_role}";
