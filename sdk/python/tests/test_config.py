from pathlib import Path

import pytest

from ken_platform_sdk.config import PlatformSettings, load_runtime

SHARED = """allow_override: [log_level]
log_level: INFO
http:
  connect_timeout_seconds: 2
  response_budget_seconds: 10
  get_retry_extra: 1
observation:
  metrics_enabled: true
"""


def test_merged_config_uses_explicit_override_and_stable_digest():
    config = load_runtime(SHARED, "log_level: DEBUG")
    assert config.log_level == "DEBUG"
    assert config.http.response_budget_seconds == 10
    assert config.digest == load_runtime(SHARED, "log_level: DEBUG").digest


def test_business_is_extensible_immutable_and_rejects_secret_or_shared_ownership():
    config = load_runtime(SHARED, "business: {max_page_size: 50, models: [{name: example}]}")
    assert config.model_dump()["business"] == {"max_page_size": 50, "models": [{"name": "example"}]}
    with pytest.raises(TypeError):
        config.business["max_page_size"] = 100
    for service in [
        "business: null",
        "business: []",
        "business: {pg_host: other}",
        "business: {models: [{api_key: private}]}",
    ]:
        with pytest.raises(ValueError):
            load_runtime(SHARED, service)
    with pytest.raises(ValueError):
        load_runtime(SHARED + "\nbusiness: {}", "{}")


@pytest.mark.parametrize(
    "service",
    [
        "http: {response_budget_seconds: 999}",
        "PG_HOST: other",
        "client_secret: never-print-this",
        "observation: {metrics_enabled: 'yes'}",
        "log_level: INFO\nlog_level: DEBUG",
        "log_level: [INFO]",
        "[]",
        "",
    ],
)
def test_invalid_snapshot_is_rejected_without_secret_in_exception(service):
    with pytest.raises(ValueError) as failure:
        load_runtime(SHARED, service)
    assert "never-print-this" not in str(failure.value)


@pytest.mark.parametrize(
    "http",
    [
        "connect_timeout_seconds: 0",
        "response_budget_seconds: -1",
        "get_retry_extra: 2",
        "get_retry_extra: true",
        "get_retry_extra: 1.0",
        "response_budget_seconds: '10'",
    ],
)
def test_bad_http_parameters_rejected(http):
    with pytest.raises(ValueError):
        load_runtime(f"http: {{{http}}}", "{}")


def settings(tmp_path: Path, **overrides):
    password = tmp_path / "nacos.password"
    password.write_text("private-credential", encoding="utf-8")
    values = {
        "project_code": "sample",
        "app_env": "local",
        "service_name": "analysis-service",
        "service_bind_port": 8100,
        "service_advertise_host": "192.0.2.7",
        "service_advertise_port": 8100,
        "nacos_server_addr": "127.0.0.1:8848",
        "nacos_namespace": "sample-local",
        "nacos_runtime_user": "runtime",
        "nacos_runtime_password_secret_file": password,
    }
    return PlatformSettings(**(values | overrides))


def test_secret_is_loaded_only_from_file_and_hidden_from_repr(tmp_path):
    config = settings(tmp_path)
    assert config.nacos_password.get_secret_value() == "private-credential"
    assert "private-credential" not in repr(config)
    assert "private-credential" not in repr(config.nacos_password)


def test_environment_binding_must_match_artifact_declaration_and_reject_admin_values(tmp_path):
    values = {key.upper(): str(value) for key, value in settings(tmp_path).model_dump().items()}
    assert (
        PlatformSettings.from_environment("analysis-service", values).service_name
        == "analysis-service"
    )
    with pytest.raises(ValueError, match="SERVICE_NAME"):
        PlatformSettings.from_environment("api-gateway", values)
    values["NACOS_ADMIN_PASSWORD"] = "must-not-reach-application"
    with pytest.raises(ValueError) as failure:
        PlatformSettings.from_environment("analysis-service", values)
    assert "must-not-reach-application" not in str(failure.value)


@pytest.mark.parametrize(
    "change",
    [
        {"nacos_namespace": "other-prod"},
        {"service_advertise_host": "127.0.0.1"},
        {"service_advertise_host": "0.0.0.0"},
        {"service_advertise_port": 0},
        {"app_env": "prod", "nacos_namespace": "sample-prod"},
    ],
)
def test_wrong_environment_or_unreachable_address_form_rejected(tmp_path, change):
    with pytest.raises(ValueError):
        settings(tmp_path, **change)
