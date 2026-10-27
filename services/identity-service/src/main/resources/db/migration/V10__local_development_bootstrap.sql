-- 仅记录首次 local 演示安装；无覆盖或增量同步状态机。
CREATE TABLE sys_local_development_bootstrap (
    id smallint PRIMARY KEY CHECK (id=1),
    project_code varchar(64) NOT NULL,
    definition_digest varchar(64) NOT NULL CHECK (definition_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON sys_local_development_bootstrap FROM PUBLIC;
GRANT SELECT,INSERT ON sys_local_development_bootstrap TO "${runtime_role}";
