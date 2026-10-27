"""新项目名称、宿主端口和 Compose 隔离的接入边界。"""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from config import contract
from config.tests.test_config_validation import valid_values


def test_new_domain_service_uses_existing_validation(tmp_path):
    (tmp_path / "application-shared.yaml").write_text("log_level: INFO\n")
    service = tmp_path / "asset-service.yaml"
    service.write_text("http:\n  connect_timeout_seconds: 2\n")
    assert contract.validate_nacos_source(tmp_path) == []
    service.write_text("PG_HOST: forbidden\n")
    assert any("禁止进入 Nacos" in x for x in contract.validate_nacos_source(tmp_path))
    service.unlink()
    (tmp_path / "Bad_Service.yaml").write_text("log_level: INFO\n")
    assert contract.validate_nacos_source(tmp_path)


@pytest.mark.parametrize("section", ["agent_runtime", "analysis_jobs"])
def test_business_execution_settings_are_not_framework_configuration(tmp_path, section):
    (tmp_path / "application-shared.yaml").write_text(
        f"{section}:\n  lease_seconds: 120\n"
    )
    assert contract.validate_nacos_source(tmp_path)


@pytest.mark.parametrize("value", ["0", "65536", "abc", "-1"])
def test_published_ports_are_validated(value):
    issues = contract.validate_env(
        contract.EnvFile(valid_values() | {"PG_PUBLISHED_PORT": value})
    )
    assert any("PG_PUBLISHED_PORT" in x for x in issues)


def test_nacos_mapped_ports_preserve_sdk_offset():
    values = valid_values() | {
        "NACOS_PUBLISHED_PORT": "18848",
        "NACOS_GRPC_PUBLISHED_PORT": "19848",
    }
    assert contract.validate_env(contract.EnvFile(values)) == []
    values["NACOS_GRPC_PUBLISHED_PORT"] = "19849"
    assert any("+ 1000" in x for x in contract.validate_env(contract.EnvFile(values)))


@pytest.mark.skipif(not shutil.which("docker"), reason="需要 Docker Compose 配置解析")
def test_two_projects_render_disjoint_resources_and_host_ports(tmp_path):
    compose = Path(__file__).resolve().parents[2] / "infra/compose.yaml"
    rendered = []
    for project, offset in (("sample-a", 0), ("sample-b", 20000)):
        values = valid_values() | {"PROJECT_CODE": project, "APP_ENV": "local"}
        values.update({k: str(v + offset) for k, v in contract.PUBLISHED_PORTS.items()})
        path = tmp_path / f"{project}.env"
        path.write_text("\n".join(f"{k}={v}" for k, v in values.items()))
        result = subprocess.run(
            [
                "docker",
                "compose",
                "--env-file",
                str(path),
                "-f",
                str(compose),
                "--profile",
                "*",
                "config",
                "--format",
                "json",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        rendered.append(json.loads(result.stdout))
    first, second = rendered
    assert first["name"] != second["name"]
    assert (
        first["networks"]["infra-net"]["name"]
        != second["networks"]["infra-net"]["name"]
    )
    assert {v["name"] for v in first["volumes"].values()}.isdisjoint(
        v["name"] for v in second["volumes"].values()
    )
    for config in rendered:
        assert all(
            "container_name" not in service for service in config["services"].values()
        )
    ports = [
        {
            p["published"]
            for service in config["services"].values()
            for p in service.get("ports", [])
        }
        for config in rendered
    ]
    assert ports[0].isdisjoint(ports[1])
