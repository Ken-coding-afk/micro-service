"""真实初始化→Flyway→运行身份，验证身份库租户底线；只使用隔离测试项目。"""

import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

import contract
import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]
RESOURCE = """
resources:
  - resource_id: identity-db
    component: postgresql
    owner_service: identity-service
    management: create
    name_binding:
      database: identity
    roles:
      owner: identity_owner
      migrator: identity_migrator
      runtime: identity_runtime
    depends_on: []
"""


def test_identity_schema_rls_foreign_keys_and_runtime_grants(
    local_env, isolated_env_factory, run_init
):
    env_file, resources = isolated_env_factory(RESOURCE)
    initialized = run_init(env_file, resources)
    assert initialized.returncode == 0, initialized.stdout + initialized.stderr
    values = contract.parse_env_file(env_file).values
    names = contract.resolve_pg_resource(
        {
            "name_binding": {"database": "identity"},
            "roles": {
                "owner": "identity_owner",
                "migrator": "identity_migrator",
                "runtime": "identity_runtime",
            },
        },
        values,
    )
    migrated = subprocess.run(
        [
            sys.executable,
            "scripts/deploy/migrate.py",
            "--env-file",
            str(env_file),
            "--resources",
            str(resources),
            "--target",
            "identity-db",
            "--tool",
            "flyway",
            "--migrations-dir",
            str(ROOT / "services/identity-service/src/main/resources/db/migration"),
            "--yes",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        timeout=90,
    )
    assert migrated.returncode == 0, migrated.stdout + migrated.stderr
    tenant_a, tenant_b, user_a, user_b, role_a = [uuid.uuid4() for _ in range(5)]
    base = {
        "host": values["PG_HOST"],
        "port": int(values["PG_PORT"]),
        "dbname": names["database"],
    }
    with psycopg.connect(
        **base,
        user=local_env["POSTGRES_ADMIN_USER"],
        password=contract.secret_value(local_env, "POSTGRES_ADMIN_PASSWORD"),
        autocommit=True,
    ) as admin:
        for tenant, code, user in (
            (tenant_a, "tenant-a", user_a),
            (tenant_b, "tenant-b", user_b),
        ):
            admin.execute(
                "INSERT INTO sys_tenant(id, code, name) VALUES (%s, %s, %s)",
                (tenant, code, code),
            )
            admin.execute(
                "INSERT INTO sys_user(id, tenant_id, normalized_username, display_name, password_hash, status, must_change_password) VALUES (%s,%s,'same-user','用户','{bcrypt}fixture','ACTIVE',false)",
                (user, tenant),
            )
        admin.execute(
            "INSERT INTO sys_role(id, tenant_id, code, name) VALUES (%s,%s,'reader','阅读员')",
            (role_a, tenant_a),
        )
        admin.execute(
            "INSERT INTO sys_permission(source,code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('PUBLISHED','document:read','阅读','document-service','TENANT','document','文档','document','文档','read',ARRAY['SELF','TENANT'],'document.read')"
        )
        admin.execute(
            "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (%s,%s,'document:read','SELF')",
            (tenant_a, role_a),
        )
    password_file = env_file.parent / "secrets/identity-db-runtime.password"
    runtime = {
        **base,
        "user": names["runtime"],
        "password": password_file.read_text(encoding="utf-8").strip(),
    }
    with psycopg.connect(**runtime, autocommit=True) as connection:
        protocol_columns = dict(
            connection.execute(
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='oauth2_authorization'"
            ).fetchall()
        )
        assert protocol_columns["attributes"] == "text"
        assert protocol_columns["access_token_issued_at"] == "timestamp with time zone"
        policy_columns = {
            row[0]
            for row in connection.execute(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='sys_client_policy'"
            ).fetchall()
        }
        assert not policy_columns.intersection(
            {"client_id", "client_secret", "allowed_scopes", "redirect_uris"}
        )
        with (
            pytest.raises(psycopg.errors.ForeignKeyViolation),
            connection.transaction(),
        ):
            connection.execute(
                "INSERT INTO sys_client_policy(registered_client_id,principal_type,audiences,status) VALUES(%s,'service',ARRAY['identity-service'],'ACTIVE')",
                (str(uuid.uuid4()),),
            )
        assert connection.execute(
            "SELECT has_table_privilege(current_user,'sys_login_session','SELECT'),has_table_privilege(current_user,'sys_login_session','INSERT'),has_table_privilege(current_user,'sys_login_session','UPDATE'),has_table_privilege(current_user,'sys_login_session','DELETE')"
        ).fetchone() == (True, True, True, False)
        with (
            pytest.raises(psycopg.errors.ForeignKeyViolation),
            connection.transaction(),
        ):
            connection.execute(
                "INSERT INTO sys_login_session(id,realm,tenant_id,user_id,auth_version,tenant_auth_version,restricted,created_at,last_activity_at) VALUES(%s,'TENANT',%s,%s,0,0,true,now(),now())",
                (uuid.uuid4(), tenant_a, user_b),
            )
        with pytest.raises(psycopg.errors.CheckViolation), connection.transaction():
            connection.execute(
                "INSERT INTO sys_login_session(id,realm,tenant_id,user_id,auth_version,tenant_auth_version,restricted,created_at,last_activity_at,redis_session_id,cleanup_pending) VALUES(%s,'TENANT',%s,%s,0,0,true,now(),now(),'test-session',true)",
                (uuid.uuid4(), tenant_a, user_a),
            )
        policy = connection.execute(
            "SELECT password_min_chars,password_max_chars,password_max_utf8_bytes,password_blocklist_enabled FROM sys_security_policy WHERE owner_type='PLATFORM'"
        ).fetchone()
        assert policy == (15, 64, 72, True)
        with pytest.raises(psycopg.errors.UniqueViolation), connection.transaction():
            connection.execute(
                "INSERT INTO sys_security_policy SELECT gen_random_uuid(),owner_type,owner_tenant_id,password_min_chars,password_max_chars,password_max_utf8_bytes,password_blocklist_enabled,password_required_character_classes,login_failure_threshold,login_failure_window_seconds,login_lock_seconds,login_ip_attempt_limit,login_ip_window_seconds,session_absolute_seconds,session_idle_seconds,recent_auth_seconds,version,created_at,updated_at FROM sys_security_policy WHERE owner_type='PLATFORM'"
            )
        with pytest.raises(psycopg.errors.CheckViolation), connection.transaction():
            connection.execute(
                "INSERT INTO sys_security_policy(owner_type,owner_tenant_id,login_ip_attempt_limit) VALUES('TENANT',%s,50)",
                (tenant_a,),
            )
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("DELETE FROM identity_technical.management_request")
        assert connection.execute("SELECT count(*) FROM sys_user").fetchone()[0] == 0
        with connection.transaction():
            connection.execute(
                "SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),)
            )
            assert connection.execute(
                "SELECT description,version,authorization_version FROM sys_role WHERE id=%s",
                (role_a,),
            ).fetchone() == ("", 0, 0)
            connection.execute(
                "UPDATE sys_role SET description=%s WHERE id=%s", ("😀" * 1024, role_a)
            )
            assert connection.execute(
                "SELECT char_length(description) FROM sys_role WHERE id=%s", (role_a,)
            ).fetchone() == (1024,)
            with (
                pytest.raises(psycopg.errors.StringDataRightTruncation),
                connection.transaction(),
            ):
                connection.execute(
                    "UPDATE sys_role SET description=%s WHERE id=%s",
                    ("x" * 1025, role_a),
                )
            assert connection.execute("SELECT id FROM sys_user").fetchall() == [
                (user_a,)
            ]
            with (
                pytest.raises(psycopg.errors.InsufficientPrivilege),
                connection.transaction(),
            ):
                connection.execute(
                    "UPDATE sys_user SET display_name='forged' WHERE id=%s RETURNING id",
                    (user_b,),
                )
                connection.execute(
                    "INSERT INTO sys_user(tenant_id,normalized_username,display_name,password_hash) VALUES (%s,'forged','伪造','fixture')",
                    (tenant_b,),
                )
            with (
                pytest.raises(psycopg.errors.ForeignKeyViolation),
                connection.transaction(),
            ):
                connection.execute(
                    "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (%s,%s,%s)",
                    (tenant_a, user_b, role_a),
                )
            with pytest.raises(psycopg.errors.CheckViolation), connection.transaction():
                connection.execute(
                    "UPDATE sys_role_permission SET data_scope=NULL WHERE role_id=%s",
                    (role_a,),
                )
            connection.execute(
                "INSERT INTO sys_management_grant(tenant_id,actor_user_id,role_id,allow_assign,allow_edit) VALUES(%s,%s,%s,true,false)",
                (tenant_a, user_a, role_a),
            )
            connection.execute(
                "INSERT INTO sys_management_grant_permission(tenant_id,actor_user_id,role_id,permission_code,max_data_scope) VALUES(%s,%s,%s,'document:read','SELF')",
                (tenant_a, user_a, role_a),
            )
            with (
                pytest.raises(psycopg.errors.ForeignKeyViolation),
                connection.transaction(),
            ):
                connection.execute(
                    "INSERT INTO sys_management_grant(tenant_id,actor_user_id,role_id,allow_assign,allow_edit) VALUES(%s,%s,%s,true,false)",
                    (tenant_a, user_b, role_a),
                )
            with pytest.raises(psycopg.errors.CheckViolation), connection.transaction():
                connection.execute(
                    "UPDATE sys_management_grant_permission SET max_data_scope='ALL' WHERE actor_user_id=%s",
                    (user_a,),
                )
            with (
                pytest.raises(psycopg.errors.InsufficientPrivilege),
                connection.transaction(),
            ):
                connection.execute(
                    "DELETE FROM sys_management_grant WHERE actor_user_id=%s", (user_a,)
                )
            assert connection.execute(
                "SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid IN ('sys_management_grant'::regclass,'sys_management_grant_permission'::regclass)"
            ).fetchall() == [(True, True), (True, True)]
        assert connection.execute("SELECT count(*) FROM sys_user").fetchone()[0] == 0
        assert (
            connection.execute("SELECT count(*) FROM sys_management_grant").fetchone()[
                0
            ]
            == 0
        )
        assert (
            connection.execute(
                "SELECT count(*) FROM sys_management_grant_permission"
            ).fetchone()[0]
            == 0
        )
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("CREATE TABLE forbidden(id int)")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("SELECT * FROM flyway_schema_history")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("DELETE FROM sys_auth_audit")
        for table in ("sys_identity_bootstrap_state", "sys_identity_bootstrap_client"):
            assert connection.execute(f"SELECT count(*) FROM {table}").fetchone() == (
                0,
            )
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                connection.execute(f"DELETE FROM {table}")
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                connection.execute(f"UPDATE {table} SET created_at=now()")
        flags = connection.execute(
            "SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"
        ).fetchone()
        assert flags == (False, False)
    output = os.getenv("PLATFORM_TEST_BINDING_OUT")
    if output:
        path = Path(output).resolve()
        assert path.is_relative_to(ROOT / ".local"), (
            "测试连接描述只能写入忽略的本地目录"
        )
        assert not path.exists(), "不覆盖另一轮测试连接描述"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    **base,
                    "app_env": "local",
                    "test_only": True,
                    "username": names["runtime"],
                    "password_file": str(password_file),
                    "observer_username": names["migrator"],
                    "observer_password_file": str(
                        env_file.parent / "secrets/identity-db-migrator.password"
                    ),
                    "tenant_a": str(tenant_a),
                    "tenant_b": str(tenant_b),
                    "user_a": str(user_a),
                    "user_b": str(user_b),
                },
                indent=2,
            ),
            encoding="utf-8",
        )
