-- 可选示例：复制到业务自己的 Flyway/Alembic 迁移，禁止 SDK 启动时自动建表。
-- 应用角色必须非 owner、非 superuser、无 BYPASSRLS。runtime_role 来自迁移绑定。
CREATE TABLE framework_outbox (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    producer varchar(64) NOT NULL,
    event_type varchar(160) NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz
);
CREATE INDEX framework_outbox_pending ON framework_outbox(tenant_id,producer,event_type,created_at,event_id)
    WHERE delivered_at IS NULL;
CREATE TABLE framework_inbox (
    consumer_name varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    body_digest varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name,event_id)
);
ALTER TABLE framework_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE framework_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE framework_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE framework_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON framework_outbox
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY tenant_isolation ON framework_inbox
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON framework_outbox TO ${runtime_role};
-- Inbox 不开放更新/删除；默认长期保留去重证据。
GRANT SELECT,INSERT ON framework_inbox TO ${runtime_role};
