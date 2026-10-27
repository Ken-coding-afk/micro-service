-- 受控维护入口记录初始资源的归属；重复初始化校验原 ID，不覆盖已改密账号或客户端。
CREATE TABLE sys_identity_bootstrap_state (
    id smallint PRIMARY KEY CHECK (id=1),
    project_code varchar(64) NOT NULL,
    app_env varchar(8) NOT NULL CHECK (app_env IN ('local','test','prod')),
    public_origin varchar(512) NOT NULL,
    initial_admin_id uuid NOT NULL REFERENCES sys_platform_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sys_identity_bootstrap_client (
    purpose varchar(160) PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL UNIQUE REFERENCES oauth2_registered_client(id),
    definition_digest varchar(64) NOT NULL CHECK (definition_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON sys_identity_bootstrap_state,sys_identity_bootstrap_client FROM PUBLIC;
GRANT SELECT,INSERT ON sys_identity_bootstrap_state,sys_identity_bootstrap_client TO "${runtime_role}";
