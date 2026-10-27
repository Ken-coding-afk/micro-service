-- R-01：委派绑定明确用户和角色；撤销保留主记录并递增 version，避免删除重建的 ABA。
CREATE TABLE sys_management_grant (
    tenant_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    allow_assign boolean NOT NULL,
    allow_edit boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, actor_user_id, role_id),
    FOREIGN KEY (tenant_id, actor_user_id) REFERENCES sys_user(tenant_id, id),
    FOREIGN KEY (tenant_id, role_id) REFERENCES sys_role(tenant_id, id)
);
CREATE INDEX sys_management_grant_role ON sys_management_grant(tenant_id, role_id, actor_user_id);

CREATE TABLE sys_management_grant_permission (
    tenant_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_code varchar(160) NOT NULL REFERENCES sys_permission(code),
    max_data_scope varchar(16) CHECK (max_data_scope IN ('SELF', 'TENANT')),
    PRIMARY KEY (tenant_id, actor_user_id, role_id, permission_code),
    FOREIGN KEY (tenant_id, actor_user_id, role_id)
        REFERENCES sys_management_grant(tenant_id, actor_user_id, role_id)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['sys_management_grant', 'sys_management_grant_permission']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    END LOOP;
END;
$$;

REVOKE ALL ON sys_management_grant, sys_management_grant_permission FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON sys_management_grant TO "${runtime_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON sys_management_grant_permission TO "${runtime_role}";
