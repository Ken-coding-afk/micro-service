-- 登录绑定是身份控制事实；Cookie/SSO 内容仍由 Spring Session Redis 保存。
CREATE TABLE sys_login_session (
    id uuid PRIMARY KEY,
    realm varchar(16) NOT NULL CHECK(realm IN ('TENANT','PLATFORM')),
    tenant_id uuid REFERENCES sys_tenant(id),
    user_id uuid,
    platform_user_id uuid REFERENCES sys_platform_user(id),
    auth_version bigint NOT NULL CHECK(auth_version>=0),
    tenant_auth_version bigint CHECK(tenant_auth_version>=0),
    restricted boolean NOT NULL,
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    revoked_at timestamptz,
    redis_session_id varchar(100) UNIQUE,
    CHECK(last_activity_at>=created_at),
    CHECK((realm='TENANT' AND tenant_id IS NOT NULL AND user_id IS NOT NULL
             AND platform_user_id IS NULL AND tenant_auth_version IS NOT NULL)
       OR (realm='PLATFORM' AND tenant_id IS NULL AND user_id IS NULL
             AND platform_user_id IS NOT NULL AND tenant_auth_version IS NULL)),
    FOREIGN KEY(tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
);
CREATE INDEX sys_login_session_user ON sys_login_session(tenant_id,user_id,created_at DESC);
CREATE INDEX sys_login_session_platform ON sys_login_session(platform_user_id,created_at DESC);
REVOKE ALL ON sys_login_session FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON sys_login_session TO "${runtime_role}";
