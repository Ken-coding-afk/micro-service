-- T-11A 样例迁移 V1:核心表 + runtime 授权。
-- 账号模型(设计 03 §6):NOLOGIN owner(t11a_fw_owner)持有对象;
-- flyway 经连接参数以 owner 身份执行 DDL;runtime(t11a_fw_runtime)仅 DML、无 DDL。
CREATE TABLE t11a_greeting (
    id        BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    title     TEXT   NOT NULL
);

CREATE TABLE t11a_backfill (
    id         BIGINT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- owner 角色后续新建的表自动授予 runtime DML(设计 10:新增表不能丢失 runtime 授权)
ALTER DEFAULT PRIVILEGES FOR ROLE t11a_fw_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO t11a_fw_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO t11a_fw_runtime;
