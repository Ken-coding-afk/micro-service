-- 身份控制表与租户业务表分开；每个服务使用独立库及 owner/migrator/runtime。
CREATE TABLE sys_tenant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9-]*$'),
    name varchar(128) NOT NULL CHECK (btrim(name) <> ''),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    expires_at timestamptz,
    organization_version bigint NOT NULL DEFAULT 0 CHECK (organization_version >= 0),
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sys_platform_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_username varchar(128) NOT NULL UNIQUE
        CHECK (normalized_username = lower(btrim(normalized_username)) AND normalized_username <> ''),
    display_name varchar(128) NOT NULL,
    password_hash text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    must_change_password boolean NOT NULL DEFAULT true,
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sys_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
    normalized_username varchar(128) NOT NULL
        CHECK (normalized_username = lower(btrim(normalized_username)) AND normalized_username <> ''),
    display_name varchar(128) NOT NULL,
    real_name varchar(128),
    employee_no varchar(64),
    contact_email varchar(320),
    phone varchar(32),
    avatar_url varchar(2048),
    description varchar(1000),
    last_login_at timestamptz,
    password_hash text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_ACTIVATION'
        CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED')),
    must_change_password boolean NOT NULL DEFAULT true,
    verified_email varchar(320),
    email_verified_at timestamptz,
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, normalized_username),
    UNIQUE (tenant_id, employee_no),
    CHECK ((verified_email IS NULL) = (email_verified_at IS NULL))
);

CREATE TABLE sys_role (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
    code varchar(128) NOT NULL CHECK (code ~ '^[a-z][a-z0-9-]*$'),
    name varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    protected boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    authorization_version bigint NOT NULL DEFAULT 0 CHECK (authorization_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    CHECK (NOT protected OR code = 'tenant-admin')
);
CREATE UNIQUE INDEX sys_role_one_protected ON sys_role(tenant_id) WHERE protected;

CREATE TABLE sys_user_role (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, role_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES sys_user(tenant_id, id),
    FOREIGN KEY (tenant_id, role_id) REFERENCES sys_role(tenant_id, id)
);
CREATE INDEX sys_user_role_members ON sys_user_role(tenant_id, role_id, user_id);

CREATE TABLE sys_permission_catalog_state (
    id smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO sys_permission_catalog_state(id) VALUES (1);

CREATE TABLE sys_permission (
    code varchar(160) PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9:_-]*$'),
    name varchar(128) NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('BUILTIN','PUBLISHED','MANUAL')),
    owner_service varchar(128) NOT NULL,
    principal_type varchar(16) NOT NULL CHECK (principal_type IN ('TENANT', 'PLATFORM')),
    group_code varchar(128) NOT NULL,
    group_name varchar(128) NOT NULL,
    resource_code varchar(128) NOT NULL,
    resource_name varchar(128) NOT NULL,
    action_code varchar(128) NOT NULL,
    description text NOT NULL DEFAULT '',
    sort integer NOT NULL DEFAULT 0,
    supported_data_scopes text[] NOT NULL DEFAULT '{}'
        CHECK (supported_data_scopes <@ ARRAY['SELF','TENANT']::text[]),
    oauth_scope varchar(160) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DEPRECATED', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_service, resource_code, action_code)
);

CREATE TABLE sys_role_permission (
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_code varchar(160) NOT NULL REFERENCES sys_permission(code),
    data_scope varchar(16) CHECK (data_scope IN ('SELF', 'TENANT')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, role_id, permission_code),
    FOREIGN KEY (tenant_id, role_id) REFERENCES sys_role(tenant_id, id)
);

CREATE FUNCTION validate_role_permission_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE permission_record sys_permission%ROWTYPE;
BEGIN
    SELECT * INTO STRICT permission_record FROM sys_permission WHERE code = NEW.permission_code;
    IF permission_record.principal_type <> 'TENANT'
       OR (NEW.data_scope IS NULL AND cardinality(permission_record.supported_data_scopes) <> 0)
       OR (NEW.data_scope IS NOT NULL AND NOT NEW.data_scope = ANY(permission_record.supported_data_scopes))
    THEN
        RAISE EXCEPTION 'role permission scope is invalid' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sys_role_permission_scope BEFORE INSERT OR UPDATE ON sys_role_permission
    FOR EACH ROW EXECUTE FUNCTION validate_role_permission_scope();

CREATE TABLE sys_tenant_capability (
    tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
    capability varchar(128) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, capability)
);

CREATE TABLE sys_auth_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid,
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('TENANT', 'PLATFORM', 'SERVICE', 'ANONYMOUS')),
    tenant_id uuid REFERENCES sys_tenant(id),
    action varchar(160) NOT NULL,
    target_id varchar(160),
    result varchar(32) NOT NULL,
    request_id varchar(64) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sys_auth_audit_tenant_time ON sys_auth_audit(tenant_id, created_at DESC, id);

-- 技术表只接收受控服务写入；跨租户投递器的受限维护角色在 T-06 接入。
CREATE SCHEMA identity_technical;
CREATE TABLE identity_technical.outbox (
    event_id uuid PRIMARY KEY,
    event_type varchar(160) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
    producer varchar(128) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    occurred_at timestamptz NOT NULL,
    traceparent varchar(55),
    data jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'QUARANTINED')),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner uuid,
    lease_expires_at timestamptz,
    published_at timestamptz,
    error_code varchar(128)
);
CREATE INDEX outbox_dispatch ON identity_technical.outbox(status, available_at, event_id);

-- FORCE 也约束表 owner；运行角色不具有任何 RLS 绕过权限。
DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['sys_user', 'sys_role', 'sys_user_role', 'sys_role_permission', 'sys_tenant_capability']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    END LOOP;
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO "${runtime_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON sys_tenant, sys_platform_user, sys_user, sys_role,
    sys_user_role, sys_permission, sys_permission_catalog_state, sys_role_permission,
    sys_tenant_capability TO "${runtime_role}";
GRANT SELECT, INSERT ON sys_auth_audit TO "${runtime_role}";
GRANT USAGE ON SCHEMA identity_technical TO "${runtime_role}";
GRANT INSERT ON identity_technical.outbox TO "${runtime_role}";

CREATE TABLE sys_user_group (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
    parent_id uuid,
    code varchar(64) NOT NULL CHECK (code ~ '^[a-z][a-z0-9_-]{0,63}$'),
    name varchar(128) NOT NULL,
    description varchar(1000) NOT NULL DEFAULT '',
    sort integer NOT NULL DEFAULT 0 CHECK (sort >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES sys_user_group(tenant_id, id),
    CHECK (id IS DISTINCT FROM parent_id)
);
CREATE INDEX sys_user_group_parent ON sys_user_group(tenant_id, parent_id, sort, id);

CREATE TABLE sys_user_group_member (
    tenant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, group_id, user_id),
    FOREIGN KEY (tenant_id, group_id) REFERENCES sys_user_group(tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES sys_user(tenant_id, id)
);
CREATE INDEX sys_user_group_member_user ON sys_user_group_member(tenant_id, user_id, group_id);

CREATE TABLE sys_user_group_role (
    tenant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    role_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, group_id, role_id),
    FOREIGN KEY (tenant_id, group_id) REFERENCES sys_user_group(tenant_id, id),
    FOREIGN KEY (tenant_id, role_id) REFERENCES sys_role(tenant_id, id)
);
CREATE INDEX sys_user_group_role_role ON sys_user_group_role(tenant_id, role_id, group_id);

CREATE FUNCTION validate_group_business_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM sys_role WHERE tenant_id=NEW.tenant_id AND id=NEW.role_id AND protected) THEN
        RAISE EXCEPTION 'protected role cannot be assigned to a group' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sys_user_group_role_business BEFORE INSERT OR UPDATE ON sys_user_group_role
    FOR EACH ROW EXECUTE FUNCTION validate_group_business_role();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['sys_user_group','sys_user_group_member','sys_user_group_role']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    END LOOP;
END;
$$;

-- 调用者权限和 RLS 生效；组织祖先停用时，不再产生该子树的组角色来源。
CREATE VIEW sys_user_role_source WITH (security_invoker=true) AS
WITH RECURSIVE active_groups AS (
    SELECT tenant_id,id,name,name::text AS group_path
    FROM sys_user_group WHERE parent_id IS NULL AND status='ACTIVE'
    UNION ALL
    SELECT child.tenant_id,child.id,child.name,parent.group_path || ' / ' || child.name
    FROM sys_user_group child JOIN active_groups parent
      ON parent.tenant_id=child.tenant_id AND parent.id=child.parent_id
    WHERE child.status='ACTIVE'
)
SELECT tenant_id,user_id,role_id,'DIRECT'::text AS source_type,
       NULL::uuid AS group_id,NULL::text AS group_name,NULL::text AS group_path
FROM sys_user_role
UNION ALL
SELECT membership.tenant_id,membership.user_id,assignment.role_id,'GROUP',
       groups.id,groups.name,groups.group_path
FROM sys_user_group_member membership
JOIN active_groups groups ON groups.tenant_id=membership.tenant_id AND groups.id=membership.group_id
JOIN sys_user_group_role assignment ON assignment.tenant_id=groups.tenant_id AND assignment.group_id=groups.id
JOIN sys_role role ON role.tenant_id=assignment.tenant_id AND role.id=assignment.role_id AND NOT role.protected;

REVOKE ALL ON sys_user_group,sys_user_group_member,sys_user_group_role,sys_user_role_source FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE,DELETE ON sys_user_group,sys_user_group_member,sys_user_group_role TO "${runtime_role}";
GRANT SELECT ON sys_user_role_source TO "${runtime_role}";
