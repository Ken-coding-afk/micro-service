"""显式选择本地示例库：真实 pg_dump/pg_restore 到新库，不覆盖原库。"""

import os
import subprocess
import uuid
from pathlib import Path

import psycopg
import pytest
from psycopg import sql

from scripts.config.contract import parse_env_file, secret_value


@pytest.mark.skipif(
    not os.getenv("PLATFORM_TEST_RECOVERY_PROJECT"), reason="需显式指定本地示例项目"
)
def test_real_pg_dump_restores_identity_data_constraints_and_rls(tmp_path):
    root = Path(os.environ["PLATFORM_TEST_RECOVERY_PROJECT"]).resolve()
    values = parse_env_file(root / ".local/environment.env").values
    assert values["APP_ENV"] == "local"
    assert values["PG_HOST"] in {"127.0.0.1", "localhost"}
    project = values["PROJECT_CODE"]
    source = project.replace("-", "_") + "_local_identity"
    restored = "restore_test_" + uuid.uuid4().hex
    container = project + "-local-infra-postgres-1"
    admin = values["POSTGRES_ADMIN_USER"]
    password = secret_value(values, "POSTGRES_ADMIN_PASSWORD")
    connection = {
        "host": values["PG_HOST"],
        "port": int(values["PG_PORT"]),
        "user": admin,
        "password": password,
        "connect_timeout": 10,
    }
    child = {**os.environ, "PGPASSWORD": password}
    tables = [
        "sys_tenant",
        "sys_user",
        "sys_role",
        "sys_user_role",
        "sys_role_permission",
    ]

    def snapshot(database):
        with psycopg.connect(**connection, dbname=database) as conn:
            data = {}
            for name in tables:
                data[name] = conn.execute(
                    sql.SQL(
                        "SELECT count(*), md5(COALESCE(string_agg(row_to_json(t)::text,"
                        " '' ORDER BY row_to_json(t)::text), '')) FROM {} t"
                    ).format(sql.Identifier(name))
                ).fetchone()
            data["rls"] = conn.execute(
                "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class "
                "WHERE relname = ANY(%s) ORDER BY relname",
                (tables,),
            ).fetchall()
            data["policies"] = conn.execute(
                "SELECT tablename, policyname, qual, with_check FROM pg_policies "
                "WHERE schemaname='public' ORDER BY tablename, policyname"
            ).fetchall()
            data["constraints"] = conn.execute(
                "SELECT conrelid::regclass::text, conname, contype, conkey, confkey, convalidated FROM pg_constraint "
                "WHERE connamespace='public'::regnamespace ORDER BY conrelid::regclass::text, conname"
            ).fetchall()
            return data

    before = snapshot(source)
    assert before["sys_tenant"][0] >= 2
    assert before["sys_user"][0] >= 2
    assert before["policies"]
    dump = subprocess.run(
        [
            "docker",
            "exec",
            "-e",
            "PGPASSWORD",
            container,
            "pg_dump",
            "-h",
            "127.0.0.1",
            "-U",
            admin,
            "-Fc",
            source,
        ],
        env=child,
        capture_output=True,
        check=False,
        timeout=60,
    )
    assert dump.returncode == 0
    backup = tmp_path / "identity.dump"
    backup.write_bytes(dump.stdout)
    backup.chmod(0o600)
    with psycopg.connect(**connection, dbname="postgres", autocommit=True) as conn:
        conn.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(restored)))
    try:
        result = subprocess.run(
            [
                "docker",
                "exec",
                "-i",
                "-e",
                "PGPASSWORD",
                container,
                "pg_restore",
                "--exit-on-error",
                "-h",
                "127.0.0.1",
                "-U",
                admin,
                "--dbname",
                restored,
            ],
            input=dump.stdout,
            env=child,
            capture_output=True,
            check=False,
            timeout=60,
        )
        assert result.returncode == 0
        after = snapshot(restored)
        assert after == before
        # pg_dump 恢复会规范化 CHECK 表达式的类型转换写法；比较结构并实际触发拒绝。
        with psycopg.connect(**connection, dbname=restored) as conn:
            tenant = conn.execute(
                "SELECT tenant_id FROM sys_user ORDER BY id LIMIT 1"
            ).fetchone()[0]
            with pytest.raises(psycopg.errors.CheckViolation), conn.transaction():
                conn.execute("UPDATE sys_role_permission SET data_scope='INVALID'")
            with pytest.raises(psycopg.errors.ForeignKeyViolation), conn.transaction():
                conn.execute(
                    "UPDATE sys_user SET tenant_id=%s WHERE id=(SELECT id FROM sys_user ORDER BY id LIMIT 1)",
                    (uuid.uuid4(),),
                )
        runtime = {
            **connection,
            "user": project.replace("-", "_") + "_local_identity_runtime",
            "password": (root / ".local/secrets/identity-db-runtime.password")
            .read_text()
            .strip(),
        }
        with psycopg.connect(**runtime, dbname=restored) as conn:
            assert conn.execute("SELECT count(*) FROM sys_user").fetchone()[0] == 0
            conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
            rows = conn.execute("SELECT DISTINCT tenant_id FROM sys_user").fetchall()
            assert rows == [(tenant,)]
        assert snapshot(source) == before
    finally:
        # 只删除此测试刚创建且名称精确匹配的隔离库；不清理示例源库。
        with psycopg.connect(**connection, dbname="postgres", autocommit=True) as conn:
            conn.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(restored)))
