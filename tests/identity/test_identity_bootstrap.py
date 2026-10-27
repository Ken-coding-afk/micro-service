"""打包后的真实维护进程：不提供 Nacos/Redis/签名密钥，验证首装、原子回滚与幂等核对。"""

import json
import os
import secrets
import subprocess
from pathlib import Path

import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]
BINDING = os.getenv("PLATFORM_TEST_BOOTSTRAP_DATABASE_BINDING")
pytestmark = pytest.mark.skipif(
    not BINDING, reason="需要独立 local/test_only 身份库绑定"
)


@pytest.fixture
def installation(tmp_path):
    binding = json.loads(Path(BINDING).read_text(encoding="utf-8"))
    assert binding["test_only"] is True and binding["app_env"] == "local"
    assert binding["dbname"].startswith("t02t")
    java = Path(os.environ["PLATFORM_TEST_JAVA_HOME"]) / "bin/java.exe"
    assert java.is_file()
    env = {
        key: value
        for key, value in os.environ.items()
        if key.upper()
        in {
            "SYSTEMROOT",
            "WINDIR",
            "PATH",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
        }
    }
    env.update(
        PROJECT_CODE=binding["dbname"].removesuffix("_local_identity"),
        APP_ENV="local",
        PUBLIC_ORIGIN="http://localhost:18080",
        PG_HOST=binding["host"],
        PG_PORT=str(binding["port"]),
        DB_NAME=binding["dbname"],
        DB_USER=binding["username"],
        DB_PASSWORD_SECRET_FILE=binding["password_file"],
    )
    values = {
        "initial_password_file": "Initial admin " + secrets.token_urlsafe(24),
        "platform_bff_secret_file": secrets.token_urlsafe(24),
        "tenant_bff_secret_file": secrets.token_urlsafe(24),
    }
    config = {
        "admin_username": "bootstrap-admin",
        "admin_display_name": "首次平台管理员",
    }
    for key, value in values.items():
        path = tmp_path / (key + ".secret")
        path.write_text(value, encoding="utf-8")
        config[key] = str(path)
    publishers = {}
    for service in ("document-service", "analysis-service"):
        path = tmp_path / (service + ".secret")
        value = secrets.token_urlsafe(24)
        path.write_text(value, encoding="utf-8")
        values[service] = value
        publishers[service] = str(path)
    config["publisher_secret_files"] = publishers
    file = tmp_path / "bootstrap.json"
    file.write_text(json.dumps(config), encoding="utf-8")
    env["IDENTITY_BOOTSTRAP_SECRET_FILE"] = str(file)
    values["database_password"] = (
        Path(binding["password_file"]).read_text(encoding="utf-8").strip()
    )
    return binding, java, env, config, file, values, tmp_path


def connect(binding, observer=False):
    user = binding["observer_username"] if observer else binding["username"]
    password = (
        binding["observer_password_file"] if observer else binding["password_file"]
    )
    options = "-c role=" + user.removesuffix("_migrator") + "_owner" if observer else ""
    return psycopg.connect(
        host=binding["host"],
        port=binding["port"],
        dbname=binding["dbname"],
        user=user,
        password=Path(password).read_text(encoding="utf-8").strip(),
        options=options,
        autocommit=True,
        connect_timeout=5,
    )


def run(installation, label, expected=0, overrides=None):
    _, java, env, _, _, values, folder = installation
    result = subprocess.run(
        [
            str(java),
            "-jar",
            str(ROOT / "services/identity-service/target/identity-service.jar"),
            "bootstrap",
        ],
        cwd=ROOT,
        env={**env, **(overrides or {})},
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
        check=False,
    )
    output = result.stdout + result.stderr
    assert not any(value in output for value in values.values()), "维护输出泄漏了秘密"
    (folder / (label + ".log")).write_text(output, encoding="utf-8")
    assert result.returncode == expected, (
        f"检查隔离日志 {label}.log，退出码 {result.returncode}"
    )
    assert "Tomcat started" not in output and "NacosRuntime" not in output
    lines = [line for line in output.splitlines() if line.startswith('{"')]
    assert len(lines) == 1
    return json.loads(lines[0])


def test_packaged_bootstrap_rolls_back_then_creates_and_only_verifies_existing_resources(
    installation,
):
    binding, _, env, config, file, values, _ = installation
    with connect(binding) as db:
        assert db.execute("SELECT count(*) FROM sys_platform_user").fetchone() == (0,)
        before_tenants = db.execute("SELECT count(*) FROM sys_tenant").fetchone()
    # 故障在双表客户端和初始账号写入之后的成功审计处触发，不能只验证前置参数拒绝。
    with connect(binding, observer=True) as owner:
        owner.execute("""CREATE FUNCTION bootstrap_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.action='identity.bootstrap.client.created' THEN
        RAISE EXCEPTION 'BOOTSTRAP_TEST_AUDIT_UNAVAILABLE'; END IF; RETURN NEW; END; $$""")
        owner.execute(
            "CREATE TRIGGER bootstrap_audit_fault BEFORE INSERT ON sys_auth_audit FOR EACH ROW EXECUTE FUNCTION bootstrap_audit_fault()"
        )
    try:
        assert run(installation, "audit-fault", 1)["status"] == "FAILED"
        with connect(binding) as db:
            for table in (
                "sys_platform_user",
                "sys_identity_bootstrap_state",
                "sys_identity_bootstrap_client",
                "oauth2_registered_client",
                "sys_client_policy",
            ):
                assert db.execute(f"SELECT count(*) FROM {table}").fetchone() == (0,)
    finally:
        with connect(binding, observer=True) as owner:
            owner.execute("DROP TRIGGER bootstrap_audit_fault ON sys_auth_audit")
            owner.execute("DROP FUNCTION bootstrap_audit_fault()")
    with connect(binding) as db:
        event = db.execute(
            "SELECT actor_type,result,details::text FROM sys_auth_audit WHERE action='identity.bootstrap.failed'"
        ).fetchall()
        assert len(event) == 1 and event[0][:2] == ("SERVICE", "FAILURE")
        assert (
            "INTERNAL_ERROR" in event[0][2]
            and "BOOTSTRAP_TEST_AUDIT_UNAVAILABLE" not in event[0][2]
        )
    created = run(installation, "created")
    assert created["status"] == "CREATED"
    assert set(created["clients"]) == {
        "PLATFORM_BFF",
        "TENANT_BFF",
        "PUBLISHER:document-service",
        "PUBLISHER:analysis-service",
    }
    with connect(binding) as db:
        admin = db.execute(
            "SELECT id,password_hash,must_change_password FROM sys_platform_user"
        ).fetchone()
        assert (
            str(admin[0]) == created["initial_admin_id"]
            and admin[1].startswith("{bcrypt}")
            and admin[2] is True
        )
        clients = db.execute(
            "SELECT id,client_id,client_secret FROM oauth2_registered_client ORDER BY client_id"
        ).fetchall()
        assert len(clients) == 4 and all(
            row[2].startswith("{bcrypt}") for row in clients
        )
        assert db.execute(
            "SELECT allowed_tenant_ids FROM sys_client_policy WHERE principal_type='user'"
        ).fetchone() == ([],)
        assert db.execute(
            "SELECT count(*) FROM sys_client_policy WHERE principal_type='service' AND cardinality(allowed_tenant_ids)=0 AND cardinality(audiences)=1"
        ).fetchone() == (2,)
        audit_count = db.execute("SELECT count(*) FROM sys_auth_audit").fetchone()
        assert (
            db.execute("SELECT count(*) FROM sys_tenant").fetchone() == before_tenants
        )
    verified = run(installation, "verified")
    assert verified == {**created, "status": "VERIFIED"}
    with connect(binding) as db:
        assert (
            db.execute(
                "SELECT id,password_hash,must_change_password FROM sys_platform_user"
            ).fetchone()
            == admin
        )
        assert (
            db.execute(
                "SELECT id,client_id,client_secret FROM oauth2_registered_client ORDER BY client_id"
            ).fetchall()
            == clients
        )
        assert (
            db.execute("SELECT count(*) FROM sys_auth_audit").fetchone() == audit_count
        )
    assert (
        run(installation, "wrong-project", 1, {"PROJECT_CODE": "another-project"})[
            "code"
        ]
        == "IDENTITY_BOOTSTRAP_CONFLICT"
    )
    assert (
        run(
            installation,
            "wrong-environment",
            1,
            {"APP_ENV": "prod", "PUBLIC_ORIGIN": "https://other.example"},
        )["code"]
        == "IDENTITY_BOOTSTRAP_CONFLICT"
    )
    secret_path = Path(config["tenant_bff_secret_file"])
    secret_path.write_text(secrets.token_urlsafe(24), encoding="utf-8")
    try:
        assert (
            run(installation, "wrong-secret", 1)["code"]
            == "IDENTITY_BOOTSTRAP_CONFLICT"
        )
    finally:
        secret_path.write_text(values["tenant_bff_secret_file"], encoding="utf-8")
    assert run(installation, "verified-after-conflicts")["status"] == "VERIFIED"
    output = os.getenv("PLATFORM_TEST_BOOTSTRAP_STATE_OUT")
    if output:
        path = Path(output).resolve()
        assert path.is_relative_to(ROOT / ".local") and not path.exists()
        path.write_text(
            json.dumps(
                {
                    "test_only": True,
                    "database_binding": str(Path(BINDING).resolve()),
                    "bootstrap_secret_file": str(file),
                    "project": env["PROJECT_CODE"],
                    "created": created,
                    "java_home": str(installation[1].parents[1]),
                },
                indent=2,
            ),
            encoding="utf-8",
        )


@pytest.mark.parametrize(
    "kind", ["unknown-field", "duplicate-field", "relative-secret", "inline-secret"]
)
def test_packaged_bootstrap_rejects_invalid_secret_descriptors_before_connecting(
    installation, kind
):
    _, _, _, config, file, _, _ = installation
    if kind == "unknown-field":
        config["unexpected"] = "DO_NOT_ECHO_RAW_BOOTSTRAP_INPUT"
    elif kind == "relative-secret":
        config["tenant_bff_secret_file"] = "relative.secret"
    elif kind == "inline-secret":
        config["platform_bff_secret"] = "DO_NOT_ECHO_RAW_BOOTSTRAP_INPUT"
    file.write_text(json.dumps(config), encoding="utf-8")
    if kind == "duplicate-field":
        file.write_text(
            '{"admin_username":"first","admin_username":"second"}', encoding="utf-8"
        )
    result = run(installation, kind, 1, {"PG_HOST": "unreachable.invalid"})
    assert result["status"] == "FAILED"
