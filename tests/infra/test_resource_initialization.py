"""资源初始化幂等/增量/冲突测试(计划 T-02 → AC-19;真实 PG + Nacos)。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import psycopg
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.config import contract

FRAMEWORK_ROOT = Path(__file__).resolve().parents[2]

ONE_DB = """
resources:
  - resource_id: svc1-db
    component: postgresql
    owner_service: svc1
    management: create
    name_binding:
      database: svc1
    roles:
      owner: svc1_owner
      migrator: svc1_migrator
      runtime: svc1_runtime
    depends_on: []
"""

TWO_DBS = (
    ONE_DB
    + """
  - resource_id: svc2-db
    component: postgresql
    owner_service: svc2
    management: create
    name_binding:
      database: svc2
    roles:
      owner: svc2_owner
      migrator: svc2_migrator
      runtime: svc2_runtime
    depends_on: []
"""
)


def _read_state(env_file: Path) -> dict:
    state = env_file.parent / "resource-state.json"
    return json.loads(state.read_text(encoding="utf-8")) if state.exists() else {}


def test_first_run_creates_and_second_verifies(
    local_env, isolated_env_factory, run_init
):
    env_file, res_path = isolated_env_factory(ONE_DB)
    result = run_init(env_file, res_path)
    assert result.returncode == 0, result.stderr
    assert _read_state(env_file)["svc1-db"]["status"] == "CREATED"

    pw_file = env_file.parent / "secrets" / "svc1-db-runtime.password"
    assert pw_file.exists()
    first_passwords = {
        p.name: p.read_text(encoding="utf-8")
        for p in (env_file.parent / "secrets").glob("svc1-*")
    }

    # 第二次执行:不改密码、状态 VERIFIED(幂等,设计 03)
    result = run_init(env_file, res_path)
    assert result.returncode == 0, result.stderr
    assert _read_state(env_file)["svc1-db"]["status"] == "VERIFIED"
    second = {
        p.name: p.read_text(encoding="utf-8")
        for p in (env_file.parent / "secrets").glob("svc1-*")
    }
    assert first_passwords == second, "重复执行不得改变已生成凭证"

    import subprocess

    logs = subprocess.run(
        ["docker", "logs", f"{local_env['PROJECT_CODE']}-postgres"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    assert all(
        value not in logs.stdout + logs.stderr for value in first_passwords.values()
    ), "新生成的明文凭据不得进入数据库日志"


def test_incremental_add_only_new_resources(local_env, isolated_env_factory, run_init):
    env_file, res_one = isolated_env_factory(ONE_DB)
    _env_file2, res_two = isolated_env_factory(TWO_DBS)
    # 用同一 PROJECT_CODE 跑两轮才能比对增量:直接复用第一份 env + 第二份清单
    result = run_init(env_file, res_one)
    assert result.returncode == 0
    result = run_init(env_file, res_two)
    assert result.returncode == 0, result.stderr
    state = _read_state(env_file)
    assert state["svc1-db"]["status"] == "VERIFIED", "既有资源保持核对通过"
    assert state["svc2-db"]["status"] == "CREATED", "只新增 svc2 资源"


def test_wrong_owner_database_conflicts(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(ONE_DB)
    prefix = contract.resolve_pg_prefix(contract.parse_env_file(env_file).values)
    db_name = f"{prefix}_svc1"
    with psycopg.connect(
        host=local_env["PG_HOST"],
        port=int(local_env["PG_PORT"]),
        user=local_env["POSTGRES_ADMIN_USER"],
        password=local_env["POSTGRES_ADMIN_PASSWORD"],
        dbname="postgres",
        autocommit=True,
    ) as conn:
        conn.execute(f'CREATE DATABASE "{db_name}"')  # owner=postgres,归属不符
    try:
        result = run_init(env_file, res_path)
        assert result.returncode == 1, "归属冲突必须非零退出"
        assert _read_state(env_file)["svc1-db"]["status"] == "CONFLICT"
        assert "CONFLICT" in result.stdout
    finally:
        with psycopg.connect(
            host=local_env["PG_HOST"],
            port=int(local_env["PG_PORT"]),
            user=local_env["POSTGRES_ADMIN_USER"],
            password=local_env["POSTGRES_ADMIN_PASSWORD"],
            dbname="postgres",
            autocommit=True,
        ) as conn:
            conn.execute(f'DROP DATABASE IF EXISTS "{db_name}"')


def test_runtime_role_cannot_ddl(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(ONE_DB)
    assert run_init(env_file, res_path).returncode == 0
    prefix = contract.resolve_pg_prefix(contract.parse_env_file(env_file).values)
    password = (env_file.parent / "secrets" / "svc1-db-runtime.password").read_text(
        encoding="utf-8"
    )
    db_name = f"{prefix}_svc1"
    with psycopg.connect(
        host=local_env["PG_HOST"],
        port=int(local_env["PG_PORT"]),
        user=f"{prefix}_svc1_runtime",
        password=password,
        dbname=db_name,
    ) as conn:
        with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
            conn.execute("CREATE TABLE should_fail (id int)")
        # 运行账号可以正常连接(SELECT 1)
        assert conn.execute("SELECT 1").fetchone() == (1,)


def test_external_verify_succeeds_with_readonly_catalog_identity(
    local_env, isolated_env_factory, run_init
):
    env_file, res_path = isolated_env_factory(ONE_DB)
    assert run_init(env_file, res_path).returncode == 0
    values = contract.parse_env_file(env_file).values
    prefix = contract.resolve_pg_prefix(values)
    migrator = f"{prefix}_svc1_migrator"
    password = (env_file.parent / "secrets/svc1-db-migrator.password").read_text(
        encoding="utf-8"
    )
    with psycopg.connect(
        host=local_env["PG_HOST"],
        port=int(local_env["PG_PORT"]),
        user=local_env["POSTGRES_ADMIN_USER"],
        password=local_env["POSTGRES_ADMIN_PASSWORD"],
        dbname="postgres",
        autocommit=True,
    ) as conn:
        conn.execute(f'ALTER ROLE "{migrator}" SET default_transaction_read_only = on')
    values.update(
        POSTGRES_ADMIN_USER=migrator,
        POSTGRES_ADMIN_PASSWORD=password,
        PG_MODE="external",
    )
    env_file.write_text(
        "".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8"
    )
    res_path.write_text(
        ONE_DB.replace("management: create", "management: verify"), encoding="utf-8"
    )
    result = run_init(env_file, res_path)
    assert result.returncode == 0, result.stdout + result.stderr
    assert _read_state(env_file)["svc1-db"]["status"] == "VERIFIED"


def test_initialized_migrator_executes_real_flyway_without_manual_grants(
    local_env, isolated_env_factory, run_init, tmp_path
):
    import subprocess

    env_file, res_path = isolated_env_factory(ONE_DB)
    assert run_init(env_file, res_path).returncode == 0
    values = contract.parse_env_file(env_file).values
    prefix = contract.resolve_pg_prefix(values)
    migrations = tmp_path / "migrations"
    migrations.mkdir()
    (migrations / "V1__probe.sql").write_text(
        "CREATE TABLE handoff_probe (id INT PRIMARY KEY);\n"
        f'GRANT SELECT, INSERT ON handoff_probe TO "{prefix}_svc1_runtime";\n',
        encoding="utf-8",
    )
    result = subprocess.run(
        [
            sys.executable,
            "scripts/deploy/migrate.py",
            "--env-file",
            str(env_file),
            "--resources",
            str(res_path),
            "--target",
            "svc1-db",
            "--tool",
            "flyway",
            "--migrations-dir",
            str(migrations),
            "--yes",
        ],
        cwd=FRAMEWORK_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
        timeout=180,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    password = (env_file.parent / "secrets/svc1-db-runtime.password").read_text(
        encoding="utf-8"
    )
    with psycopg.connect(
        host=values["PG_HOST"],
        port=int(values["PG_PORT"]),
        user=f"{prefix}_svc1_runtime",
        password=password,
        dbname=f"{prefix}_svc1",
    ) as conn:
        conn.execute("INSERT INTO handoff_probe VALUES (1)")
        assert conn.execute("SELECT id FROM handoff_probe").fetchone() == (1,)
        with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
            conn.execute("CREATE TABLE forbidden_handoff (id INT)")


def test_nacos_namespace_idempotent(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(
        ONE_DB
        + """
  - resource_id: nacos-namespace
    component: nacos
    owner_service: platform
    management: create
    name_binding:
      namespace: default
    depends_on: []
"""
    )
    assert run_init(env_file, res_path).returncode == 0
    assert _read_state(env_file)["nacos-namespace"]["status"] == "CREATED"
    assert run_init(env_file, res_path).returncode == 0
    assert _read_state(env_file)["nacos-namespace"]["status"] == "VERIFIED"


def test_existing_runtime_bypassrls_is_rejected(
    local_env, isolated_env_factory, run_init
):
    env_file, res_path = isolated_env_factory(ONE_DB)
    assert run_init(env_file, res_path).returncode == 0
    prefix = contract.resolve_pg_prefix(contract.parse_env_file(env_file).values)
    runtime = f"{prefix}_svc1_runtime"
    with psycopg.connect(
        host=local_env["PG_HOST"],
        port=int(local_env["PG_PORT"]),
        user=local_env["POSTGRES_ADMIN_USER"],
        password=local_env["POSTGRES_ADMIN_PASSWORD"],
        dbname="postgres",
        autocommit=True,
    ) as conn:
        conn.execute(f'ALTER ROLE "{runtime}" BYPASSRLS')
        try:
            result = run_init(env_file, res_path)
            assert result.returncode == 1
            assert _read_state(env_file)["svc1-db"]["status"] == "CONFLICT"
        finally:
            conn.execute(f'ALTER ROLE "{runtime}" NOBYPASSRLS')


def test_real_rabbit_resources_idempotent_and_verify_does_not_repair(
    local_env, isolated_env_factory, run_init
):
    import httpx

    resources = """
resources:
  - resource_id: rabbit-vhost
    component: rabbitmq
    owner_service: platform
    management: create
    name_binding:
      vhost: default
    roles:
      app: app
    depends_on: []
"""
    env_file, res_path = isolated_env_factory(resources)
    values = contract.parse_env_file(env_file).values
    values.update(
        RABBIT_HOST="127.0.0.1",
        RABBIT_ADMIN_USER=local_env["RABBIT_ADMIN_USER"],
        RABBIT_ADMIN_PASSWORD=local_env["RABBIT_ADMIN_PASSWORD"],
    )
    env_file.write_text(
        "".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8"
    )
    namespace = values["NACOS_NAMESPACE"]
    username = f"{contract.resolve_prefix(namespace)}_app"
    base = f"http://{values['RABBIT_HOST']}:{values['RABBIT_MGMT_PORT']}"
    with httpx.Client(
        base_url=base,
        auth=(values["RABBIT_ADMIN_USER"], values["RABBIT_ADMIN_PASSWORD"]),
        timeout=10,
    ) as client:
        try:
            first = run_init(env_file, res_path)
            assert first.returncode == 0, first.stdout + first.stderr
            assert _read_state(env_file)["rabbit-vhost"]["status"] == "CREATED"
            secret = env_file.parent / "secrets/rabbit-app.password"
            original = secret.read_bytes()
            assert run_init(env_file, res_path).returncode == 0
            assert secret.read_bytes() == original
            assert _read_state(env_file)["rabbit-vhost"]["status"] == "VERIFIED"
            permission = f"/api/permissions/{namespace}/{username}"
            assert client.delete(permission).status_code == 204
            res_path.write_text(
                resources.replace("management: create", "management: verify"),
                encoding="utf-8",
            )
            assert run_init(env_file, res_path).returncode == 1
            assert client.get(permission).status_code == 404
        finally:
            client.delete(f"/api/vhosts/{namespace}")
            client.delete(f"/api/users/{username}")


def _project_of(env_file: Path) -> str:
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        if raw.startswith("PROJECT_CODE="):
            return raw.partition("=")[2].strip()
    raise AssertionError("环境文件缺少 PROJECT_CODE")
