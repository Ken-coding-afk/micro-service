"""本地示例生成的服务凭据边界与重复操作保护。"""

import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "sample_bindings", Path(__file__).parents[1] / "verification/bind_business.py"
)
bindings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bindings)


def fixture(tmp_path):
    env = tmp_path / "environment.env"
    env.write_text(
        "APP_ENV=local\nPROJECT_CODE=sample-a\nNACOS_RUNTIME_PASSWORD=nacos-test\n"
    )
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    (runtime / "identity-service.env").write_text(
        "APP_ENV=local\nPROJECT_CODE=sample-a\nPG_HOST=postgres\nPG_PORT=5432\n"
        "DB_NAME=identity\nDB_USER=identity_runtime\n"
        "IDENTITY_SIGNING_JWK_SECRET_FILE=/run/secrets/signing.json\n"
        "REDIS_PASSWORD_SECRET_FILE=/run/secrets/redis.password\n"
    )
    (runtime / "api-gateway.env").write_text("APP_ENV=local\n")
    (runtime / "compose.env").write_text("APP_ENV=local\n")
    gateway = runtime / "api-gateway-secrets"
    gateway.mkdir()
    (gateway / "bff.json").write_text(
        '{"platform_secret_file":"/run/secrets/platform.password"}'
    )
    secrets = tmp_path / "secrets"
    secrets.mkdir()
    state = {"web": {"client_secret": "web-test"}}
    for lang in ("java", "python"):
        (secrets / f"sample-{lang}-db-runtime.password").write_text(lang + "-db")
        for consumer in ("resource", "gateway"):
            state[lang + "-" + consumer] = {"client_secret": lang + "-" + consumer}
    (tmp_path / "business-credentials.json").write_text(json.dumps(state))
    return env, runtime


def test_business_bindings_do_not_include_identity_privileges(tmp_path):
    env, runtime = fixture(tmp_path)
    bindings.bind(env, "java-image", "python-image")
    for lang in ("java", "python"):
        values = bindings.contract.parse_env_file(
            runtime / f"sample-{lang}-service.env"
        ).values
        assert values["DB_NAME"] == "sample_a_local_sample_" + lang
        assert values["DB_USER"].endswith("_runtime")
        literal = bindings.runtime_values(runtime / f"sample-{lang}-service.env")
        assert literal["DB_PASSWORD_SECRET_FILE"] == "/run/secrets/database.password"
        assert not any(key.startswith(("IDENTITY_", "REDIS_")) for key in values)
        secret_dir = runtime / f"sample-{lang}-service-secrets"
        assert {p.name for p in secret_dir.iterdir()} == {
            "database.password",
            "introspection.password",
            "nacos-runtime.password",
        }
        assert (secret_dir / "introspection.password").read_text() == lang + "-resource"
    before = {str(p): p.read_bytes() for p in runtime.rglob("*") if p.is_file()}
    bindings.bind(env, "java-image", "python-image")
    assert {str(p): p.read_bytes() for p in runtime.rglob("*") if p.is_file()} == before


def test_changed_secret_is_not_overwritten(tmp_path):
    env, runtime = fixture(tmp_path)
    bindings.bind(env, "java-image", "python-image")
    secret = runtime / "sample-java-service-secrets/introspection.password"
    secret.write_text("rotated-secret")
    with pytest.raises(ValueError, match="拒绝覆盖"):
        bindings.bind(env, "java-image", "python-image")
    assert secret.read_text() == "rotated-secret"


def test_production_environment_is_rejected_before_writes(tmp_path):
    env, runtime = fixture(tmp_path)
    env.write_text("APP_ENV=production\nPROJECT_CODE=sample-a\n")
    with pytest.raises(ValueError, match="仅支持本地"):
        bindings.bind(env, "java-image", "python-image")
    assert not (runtime / "sample-java-service-secrets").exists()
