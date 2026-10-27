import argparse
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


def test_reload_watches_business_and_editable_sdk_sources(tmp_path, monkeypatch):
    directory = tmp_path / "services/inspection"
    record = {"language": "python", "app_factory": "inspection:create_app"}
    monkeypatch.setattr(dev, "project_config", lambda root: {"services": {"inspection": record}})
    monkeypatch.setattr(dev, "service_directory", lambda root, item: directory)
    monkeypatch.setattr(dev, "run", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        dev,
        "prepare",
        lambda *args, **kwargs: (
            directory,
            [],
            {"SERVICE_BIND_HOST": "127.0.0.1", "SERVICE_BIND_PORT": "8083"},
        ),
    )
    calls = []

    class Process:
        def __init__(self, command, **kwargs):
            calls.append((command, kwargs))

        def wait(self):
            return 0

        def poll(self):
            return 0

    monkeypatch.setattr(dev.subprocess, "Popen", Process)
    assert (
        dev.dev(
            tmp_path,
            argparse.Namespace(
                service="inspection", reload=True, prepare_only=False, tenant_code=""
            ),
        )
        == 0
    )
    command, options = calls[0]
    watches = [command[i + 1] for i, value in enumerate(command) if value == "--reload-dir"]
    assert watches == [str(directory / "src"), str(tmp_path / "sdk/python/src")]
    assert options["cwd"] == directory


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
