"""配置字段归属合同与发布漂移测试(计划 T-02 → AC-18)。

单元部分不依赖组件;漂移部分需要真实 Nacos(设计 02·配置发布)。
"""

from __future__ import annotations

import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.config import contract

FRAMEWORK_ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# 单元:字段归属与来源校验
# ---------------------------------------------------------------------------


def test_unknown_env_field_rejected(tmp_path):
    env_file = tmp_path / "environment.env"
    env_file.write_text(
        "PROJECT_CODE=doc-insight\nAPP_ENV=local\nNOT_A_FIELD=1\n", encoding="utf-8"
    )
    issues = contract.validate_env(contract.parse_env_file(env_file))
    assert any("未知环境字段" in i and "NOT_A_FIELD" in i for i in issues)


def test_missing_required_env_field_reported(tmp_path):
    env_file = tmp_path / "environment.env"
    env_file.write_text("PROJECT_CODE=doc-insight\n", encoding="utf-8")
    issues = contract.validate_env(contract.parse_env_file(env_file))
    assert any("缺少必填环境字段: APP_ENV" in i for i in issues)


@pytest.mark.parametrize("key", ["pg_host", "gateway_bff_clients_secret_file"])
def test_nacos_source_rejects_env_binding_keys(tmp_path, key):
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (source / "identity-service.yaml").write_text(
        f"{key}: forbidden-value\n", encoding="utf-8"
    )
    issues = contract.validate_nacos_source(source)
    assert any("禁止进入 Nacos" in i for i in issues)


def test_nacos_service_override_only_allowlisted(tmp_path):
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "allow_override:\n  - log_level\nlog_level: INFO\nhttp:\n"
        "  connect_timeout_seconds: 2\n",
        encoding="utf-8",
    )
    (source / "identity-service.yaml").write_text(
        "log_level: DEBUG\nhttp:\n  connect_timeout_seconds: 5\n", encoding="utf-8"
    )
    issues = contract.validate_nacos_source(source)
    assert any(
        "未声明 allow_override" in i and "connect_timeout_seconds" in i for i in issues
    )


def test_committed_nacos_source_valid():
    issues = contract.validate_nacos_source(FRAMEWORK_ROOT / "config/nacos")
    assert issues == []


def test_business_config_is_extensible_but_not_a_secret_or_common_config_escape(
    tmp_path,
):
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf8"
    )
    service = tmp_path / "sample-service.yaml"
    service.write_text(
        "business:\n  limits:\n    max_page_size: 50\n  models:\n    - name: sample\n",
        encoding="utf8",
    )
    assert contract.validate_nacos_source(tmp_path) == []
    for content in [
        "business: null",
        "business:\n  models:\n    - api_key: secret",
        "business:\n  pg_host: other",
        "unknown: true",
    ]:
        service.write_text(content, encoding="utf8")
        assert contract.validate_nacos_source(tmp_path)
    service.write_text("{}", encoding="utf8")
    (tmp_path / "application-shared.yaml").write_text("business: {}", encoding="utf8")
    assert contract.validate_nacos_source(tmp_path)


@pytest.mark.parametrize(
    "fields",
    [
        {"project_display_name": "<DO_NOT_ECHO_BRAND>"},
        {"project_display_name": None},
        {"project_display_name": True},
        {"logo_url": "https://untrusted.example/logo.svg"},
        {"logo_url": "//untrusted.example/logo.svg"},
        {"logo_url": "/a/%2e%2e/logo.svg"},
        {"logo_url": "/logo%0d%0a.svg"},
        {"logo_url": "/logo.svg?credential=DO_NOT_ECHO_BRAND"},
        {"logo_url": "relative.svg"},
        {"logo_url": "/lo\ngo.svg"},
        {"logo_url": "/lo go.svg"},
        {"logo_url": "/logo%xz.svg"},
    ],
)
def test_identity_brand_rejects_unsafe_values_without_echo(fields):
    issues = contract._check_identity_brand(fields, "identity-service.yaml")
    assert issues
    assert "DO_NOT_ECHO_BRAND" not in str(issues)


def test_identity_brand_is_owned_by_identity_and_public_route_is_exact():
    fields = {
        "project_display_name": "文档智能平台",
        "logo_url": "/identity/admin/assets/logo.svg",
    }
    assert contract._check_identity_brand(fields, "identity-service.yaml") == []
    assert contract._check_identity_brand(fields, "application-shared.yaml")
    assert contract._check_identity_brand(fields, "document-service.yaml")
    route = {
        "id": "identity-brand",
        "path": "/api/v1/identity/admin-config",
        "service": "identity-service",
    }
    # 身份公开白名单已由网关固定提供，旧 DSL 的精确路径与通配路径均须迁移。
    assert contract._check_forbidden({"gateway": {"routes": [route]}}, "api-gateway.yaml")
    route["path"] += "/**"
    assert contract._check_forbidden({"gateway": {"routes": [route]}}, "api-gateway.yaml")


# ---------------------------------------------------------------------------
# 集成:发布与漂移拒绝(真实 Nacos)
# ---------------------------------------------------------------------------


def test_publish_then_drift_refused(
    local_env, tmp_path, monkeypatch, isolated_env_factory, run_init
):
    pytest.importorskip("httpx")
    from tools.config import publish as publish_module

    resource = """
resources:
  - resource_id: nacos-namespace
    component: nacos
    owner_service: platform
    management: create
    name_binding:
      namespace: default
    depends_on: []
"""
    env_file, resource_file = isolated_env_factory(resource)
    initialized = run_init(env_file, resource_file)
    assert initialized.returncode == 0, initialized.stdout + initialized.stderr
    values = contract.parse_env_file(env_file).values
    namespace = values["NACOS_NAMESPACE"]
    server = values["NACOS_SERVER_ADDR"]
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )

    # 状态文件隔离:让 publish 状态落在 tmp_path
    monkeypatch.setattr(publish_module, "FRAMEWORK_ROOT", tmp_path)
    tmp_path.joinpath(".local").mkdir(exist_ok=True)
    # contract 校验依赖的真实源目录保持指向临时源
    args = type(
        "A", (), {"env_file": str(env_file), "source": str(source), "dry_run": False}
    )()
    assert publish_module.publish(args) == 0, "首次发布应成功"

    args.dry_run = False
    assert publish_module.publish(args) == 0, "重复发布(未变更)应成功"

    # 直接经 admin API 篡改远端 → 下次发布必须拒绝覆盖(漂移)
    with httpx.Client(timeout=10) as client:
        resp = client.post(
            f"{local_env['NACOS_CONSOLE_ADDR']}/v3/auth/user/login",
            data={
                "username": local_env["NACOS_ADMIN_USER"],
                "password": local_env["NACOS_ADMIN_PASSWORD"],
            },
        )
        token = resp.json()["accessToken"]
        tampered = client.post(
            f"http://{server}/nacos/v3/admin/cs/config",
            data={
                "accessToken": token,
                "dataId": "application-shared.yaml",
                "groupName": "DEFAULT_GROUP",
                "namespaceId": namespace,
                "content": "log_level: WARN  # 控制台漂移",
                "type": "yaml",
            },
        )
        assert tampered.status_code == 200

    assert publish_module.publish(args) == 1, "漂移后发布必须拒绝(exit 1)"
