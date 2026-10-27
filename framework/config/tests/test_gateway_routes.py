"""与 Java 共用路由合同，发布前拒绝公开旁路和非 Gateway 配置归属。"""

import json
from pathlib import Path

import pytest
import yaml

from config.contract import ENV_FIELDS, NACOS_FORBIDDEN_KEYS, validate_nacos_source

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json.loads(
    (ROOT / "test-fixtures/gateway-routes-v1.json").read_text(encoding="utf-8")
)
assert FIXTURE["test_only"] is True


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda case: case["name"])
def test_shared_gateway_route_contract(tmp_path, case):
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (tmp_path / f"{case['service']}.yaml").write_text(
        yaml.safe_dump({"gateway": case["block"]}), encoding="utf-8"
    )
    assert (validate_nacos_source(tmp_path) == []) == case["valid"]


def test_gateway_readers_are_secret_file_binding_and_never_remote_configuration(
    tmp_path,
):
    field = "GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE"
    assert ENV_FIELDS[field]["category"] == "secret_ref"
    assert field in NACOS_FORBIDDEN_KEYS
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (tmp_path / "api-gateway.yaml").write_text(
        f"{field.lower()}: private\n", encoding="utf-8"
    )
    assert any("禁止进入 Nacos" in issue for issue in validate_nacos_source(tmp_path))


def test_shared_configuration_cannot_publish_gateway_routes(tmp_path):
    (tmp_path / "application-shared.yaml").write_text(
        "gateway: {routes: []}\n", encoding="utf-8"
    )
    assert any("Gateway 显式路由" in issue for issue in validate_nacos_source(tmp_path))
