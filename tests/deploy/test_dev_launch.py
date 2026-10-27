import importlib.util
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "scripts"))

spec = importlib.util.spec_from_file_location(
    "dev_launch", Path(__file__).parents[2] / "scripts/dev.py"
)
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)


def project(root):
    (root / "config").mkdir()
    (root / "services/items/target").mkdir(parents=True)
    (root / "services/items/target/items.jar").write_bytes(b"fixture")
    import yaml

    records = {
        name: {
            "source": "local",
            "language": "java",
            "directory": "services/" + name,
            "requires": [],
        }
        for name in ("identity-service", "api-gateway", "items")
    }
    (root / "config/services.yaml").write_text(
        yaml.safe_dump({"services": records}), encoding="utf-8"
    )
    (root / "config/development.yaml").write_text(
        yaml.safe_dump(
            {
                "local": {"project": "sample", "port_offset": 0},
                "services": {"items": {"port": 8082}},
            }
        ),
        encoding="utf-8",
    )
    env = root / "runtime.env"
    env.write_text(
        "PROJECT_CODE=sample\nAPP_ENV=local\nNACOS_NAMESPACE=sample-local\n"
        "SERVICE_NAME=items\nSERVICE_BIND_PORT=8082\n"
        "SERVICE_ADVERTISE_HOST=items\nSERVICE_ADVERTISE_PORT=8082\n",
        encoding="utf-8",
    )
    host = root / "host.env"
    host.write_text("SERVICE_ADVERTISE_HOST=host.docker.internal\n", encoding="utf-8")
    return env, host


def test_registered_entry_uses_only_runtime_binding_and_allowed_host_override(
    tmp_path, monkeypatch
):
    env, host = project(tmp_path)
    monkeypatch.setenv("POSTGRES_ADMIN_PASSWORD", "must-not-inherit")
    directory, command, child = dev.prepare(tmp_path, "items", env, host)
    assert directory == tmp_path / "services/items"
    assert command == ["java", "-jar", str(directory / "target/items.jar")]
    assert child["SERVICE_ADVERTISE_HOST"] == "host.docker.internal"
    assert "POSTGRES_ADMIN_PASSWORD" not in child


@pytest.mark.parametrize(
    "override",
    [
        "APP_ENV=prod\n",
        "NACOS_NAMESPACE=another-local\n",
        "PUBLIC_ORIGIN=https://elsewhere.example\n",
        "DB_USER=postgres\n",
        "NO_PROXY=*\n",
        "SERVICE_BIND_PORT=123\nSERVICE_BIND_PORT=456\n",
    ],
)
def test_host_override_rejects_identity_and_duplicate_fields(tmp_path, override):
    env, host = project(tmp_path)
    host.write_text(override, encoding="utf-8")
    with pytest.raises(ValueError):
        dev.prepare(tmp_path, "items", env, host)


def test_mismatched_service_and_privileged_binding_are_rejected(tmp_path):
    env, host = project(tmp_path)
    with pytest.raises(ValueError):
        dev.prepare(tmp_path, "other", env, host)
    env.write_text(env.read_text() + "POSTGRES_ADMIN_PASSWORD=unsafe\n")
    with pytest.raises(ValueError):
        dev.prepare(tmp_path, "items", env, host)
