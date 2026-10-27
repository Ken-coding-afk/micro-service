-- 安全策略属于身份控制数据：专用 repository 按主体/租户读取，不向业务服务开放。
CREATE TABLE sys_security_policy (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type varchar(16) NOT NULL CHECK (owner_type IN ('PLATFORM','TENANT')),
    owner_tenant_id uuid REFERENCES sys_tenant(id),
    password_min_chars integer CHECK (password_min_chars BETWEEN 15 AND 32),
    password_max_chars integer CHECK (password_max_chars = 64),
    password_max_utf8_bytes integer CHECK (password_max_utf8_bytes = 72),
    password_blocklist_enabled boolean CHECK (password_blocklist_enabled),
    password_required_character_classes text[] CHECK (cardinality(password_required_character_classes) = 0),
    login_failure_threshold integer CHECK (login_failure_threshold BETWEEN 1 AND 10),
    login_failure_window_seconds integer CHECK (login_failure_window_seconds BETWEEN 900 AND 3600),
    login_lock_seconds integer CHECK (login_lock_seconds BETWEEN 900 AND 3600),
    login_ip_attempt_limit integer CHECK (login_ip_attempt_limit BETWEEN 1 AND 60),
    login_ip_window_seconds integer CHECK (login_ip_window_seconds BETWEEN 60 AND 300),
    session_absolute_seconds integer CHECK (session_absolute_seconds BETWEEN 300 AND 28800),
    session_idle_seconds integer CHECK (session_idle_seconds BETWEEN 60 AND 1800),
    recent_auth_seconds integer CHECK (recent_auth_seconds BETWEEN 60 AND 300),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (owner_type, owner_tenant_id),
    CHECK ((owner_type = 'PLATFORM' AND owner_tenant_id IS NULL) OR (owner_type = 'TENANT' AND owner_tenant_id IS NOT NULL)),
    CHECK (owner_type <> 'TENANT' OR (login_ip_attempt_limit IS NULL AND login_ip_window_seconds IS NULL)),
    CHECK (session_idle_seconds IS NULL OR session_absolute_seconds IS NULL OR session_idle_seconds <= session_absolute_seconds),
    CHECK (owner_type <> 'PLATFORM' OR (
        password_min_chars IS NOT NULL AND password_max_chars IS NOT NULL AND password_max_utf8_bytes IS NOT NULL
        AND password_blocklist_enabled IS NOT NULL AND password_required_character_classes IS NOT NULL
        AND login_failure_threshold IS NOT NULL AND login_failure_window_seconds IS NOT NULL AND login_lock_seconds IS NOT NULL
        AND login_ip_attempt_limit IS NOT NULL AND login_ip_window_seconds IS NOT NULL
        AND session_absolute_seconds IS NOT NULL AND session_idle_seconds IS NOT NULL AND recent_auth_seconds IS NOT NULL))
);
INSERT INTO sys_security_policy(owner_type,password_min_chars,password_max_chars,password_max_utf8_bytes,
    password_blocklist_enabled,password_required_character_classes,login_failure_threshold,
    login_failure_window_seconds,login_lock_seconds,login_ip_attempt_limit,login_ip_window_seconds,
    session_absolute_seconds,session_idle_seconds,recent_auth_seconds)
VALUES('PLATFORM',15,64,72,true,'{}',10,900,900,60,60,28800,1800,300);

-- 附加名单只保存与运行时一致的小写 UTF-8 SHA-256；配置写入口在安全策略任务接入。
CREATE TABLE sys_password_blocklist (
    password_digest varchar(64) PRIMARY KEY CHECK (password_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 非天然幂等的管理创建：只保存非秘密结果，重复请求不能重放初始凭证。
CREATE TABLE identity_technical.management_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('PLATFORM','TENANT')),
    actor_id uuid NOT NULL,
    tenant_id uuid REFERENCES sys_tenant(id),
    action varchar(160) NOT NULL,
    request_id varchar(64) NOT NULL,
    request_digest varchar(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    result_data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (actor_type,actor_id,tenant_id,action,request_id)
);
REVOKE ALL ON sys_security_policy,sys_password_blocklist,identity_technical.management_request FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON sys_security_policy TO "${runtime_role}";
GRANT SELECT,INSERT ON sys_password_blocklist,identity_technical.management_request TO "${runtime_role}";
