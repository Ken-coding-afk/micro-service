"""资源初始化幂等/增量/冲突测试(计划 T-02 → AC-19;真实 PG + Nacos)。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import psycopg
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from config import contract  # noqa: E402

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

TWO_DBS = ONE_DB + """
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


@pytest.fixture(autouse=True)
def _clean_leftover_state():
    """隔离历史运行残留:svc* 资源的 state 键在每次测试前清除。

    库/角色名含唯一 uuid 前缀天然隔离,但 state 以 resource_id 为键,
    上一轮运行会留下 svc1/svc2 状态导致 CREATED 断言失效。
    """
    state_path = FRAMEWORK_ROOT / ".local" / "resource-state.json"
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        changed = False
        for key in [k for k in state if k.startswith(("svc1", "svc2"))]:
            del state[key]
            changed = True
        if changed:
            state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2),
                                  encoding="utf-8")
    yield


def _read_state() -> dict:
    state = FRAMEWORK_ROOT / ".local" / "resource-state.json"
    return json.loads(state.read_text(encoding="utf-8")) if state.exists() else {}


def test_first_run_creates_and_second_verifies(local_env, isolated_env_factory,
                                               run_init):
    env_file, res_path = isolated_env_factory(ONE_DB)
    result = run_init(env_file, res_path)
    assert result.returncode == 0, result.stderr
    assert _read_state()["svc1-db"]["status"] == "CREATED"

    prefix = contract.resolve_prefix(_project_of(env_file))
    pw_file = FRAMEWORK_ROOT / ".local" / "secrets" / "svc1-db-runtime.password"
    assert pw_file.exists()
    first_passwords = {
        p.name: p.read_text(encoding="utf-8")
        for p in (FRAMEWORK_ROOT / ".local" / "secrets").glob("svc1-*")
    }

    # 第二次执行:不改密码、状态 VERIFIED(幂等,设计 03)
    result = run_init(env_file, res_path)
    assert result.returncode == 0, result.stderr
    assert _read_state()["svc1-db"]["status"] == "VERIFIED"
    second = {
        p.name: p.read_text(encoding="utf-8")
        for p in (FRAMEWORK_ROOT / ".local" / "secrets").glob("svc1-*")
    }
    assert first_passwords == second, "重复执行不得改变已生成凭证"


def test_incremental_add_only_new_resources(local_env, isolated_env_factory,
                                            run_init):
    env_file, res_one = isolated_env_factory(ONE_DB)
    env_file2, res_two = isolated_env_factory(TWO_DBS)
    # 用同一 PROJECT_CODE 跑两轮才能比对增量:直接复用第一份 env + 第二份清单
    result = run_init(env_file, res_one)
    assert result.returncode == 0
    result = run_init(env_file, res_two)
    assert result.returncode == 0, result.stderr
    state = _read_state()
    assert state["svc1-db"]["status"] == "VERIFIED", "既有资源保持核对通过"
    assert state["svc2-db"]["status"] == "CREATED", "只新增 svc2 资源"


def test_wrong_owner_database_conflicts(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(ONE_DB)
    prefix = contract.resolve_prefix(_project_of(env_file))
    db_name = f"{prefix}_svc1"
    with psycopg.connect(host=local_env["PG_HOST"], port=int(local_env["PG_PORT"]),
                         user=local_env["POSTGRES_ADMIN_USER"],
                         password=local_env["POSTGRES_ADMIN_PASSWORD"],
                         dbname="postgres", autocommit=True) as conn:
        conn.execute(f'CREATE DATABASE "{db_name}"')  # owner=postgres,归属不符
    try:
        result = run_init(env_file, res_path)
        assert result.returncode == 1, "归属冲突必须非零退出"
        assert _read_state()["svc1-db"]["status"] == "CONFLICT"
        assert "CONFLICT" in result.stdout
    finally:
        with psycopg.connect(host=local_env["PG_HOST"], port=int(local_env["PG_PORT"]),
                             user=local_env["POSTGRES_ADMIN_USER"],
                             password=local_env["POSTGRES_ADMIN_PASSWORD"],
                             dbname="postgres", autocommit=True) as conn:
            conn.execute(f'DROP DATABASE IF EXISTS "{db_name}"')


def test_runtime_role_cannot_ddl(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(ONE_DB)
    assert run_init(env_file, res_path).returncode == 0
    prefix = contract.resolve_prefix(_project_of(env_file))
    password = (FRAMEWORK_ROOT / ".local" / "secrets"
                / "svc1-db-runtime.password").read_text(encoding="utf-8")
    db_name = f"{prefix}_svc1"
    with psycopg.connect(host=local_env["PG_HOST"], port=int(local_env["PG_PORT"]),
                         user=f"{prefix}_svc1_runtime", password=password,
                         dbname=db_name) as conn:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            with conn.transaction():
                conn.execute("CREATE TABLE should_fail (id int)")
        # 运行账号可以正常连接(SELECT 1)
        assert conn.execute("SELECT 1").fetchone() == (1,)


def test_nacos_namespace_idempotent(local_env, isolated_env_factory, run_init):
    env_file, res_path = isolated_env_factory(
        ONE_DB + """
  - resource_id: nacos-namespace
    component: nacos
    owner_service: platform
    management: create
    name_binding:
      namespace: default
    depends_on: []
""")
    assert run_init(env_file, res_path).returncode == 0
    assert _read_state()["nacos-namespace"]["status"] == "CREATED"
    assert run_init(env_file, res_path).returncode == 0
    assert _read_state()["nacos-namespace"]["status"] == "VERIFIED"


def _project_of(env_file: Path) -> str:
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        if raw.startswith("PROJECT_CODE="):
            return raw.partition("=")[2].strip()
    raise AssertionError("环境文件缺少 PROJECT_CODE")
