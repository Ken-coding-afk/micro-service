"""配置发布前拒绝错误环境/来源；不依赖外部组件。"""

import pytest

from scripts.config import contract


def valid_values():
    return {
        "PROJECT_CODE": "sample",
        "APP_ENV": "local",
        "NACOS_NAMESPACE": "sample-local",
        "PUBLIC_ORIGIN": "http://localhost:18080",
        "IDENTITY_INTERNAL_ORIGIN": "http://identity-service:8081",
        "NACOS_CONSOLE_ADDR": "http://127.0.0.1:8080",
        "NACOS_SERVER_ADDR": "127.0.0.1:8848",
        "PG_HOST": "127.0.0.1",
        "PG_PORT": "15432",
        "REDIS_HOST": "127.0.0.1",
        "REDIS_PORT": "16379",
    }


@pytest.mark.parametrize("host", ["0.0.0.0", "::", "8.8.8.8", "localhost"])
def test_infra_bind_rejects_wildcard_public_and_non_ip(host):
    assert contract.validate_env(
        contract.EnvFile(valid_values() | {"INFRA_BIND_HOST": host})
    )


@pytest.mark.parametrize("host", ["127.0.0.1", "10.20.0.10"])
def test_infra_bind_accepts_explicit_local_or_private_ip(host):
    assert (
        contract.validate_env(
            contract.EnvFile(valid_values() | {"INFRA_BIND_HOST": host})
        )
        == []
    )


def test_identity_signing_reference_is_declared_but_never_nacos(tmp_path):
    key = tmp_path / "identity-signing.json"
    key.write_text('{"test_only": true}', encoding="utf-8")
    field = "IDENTITY_SIGNING_JWK_SECRET_FILE"
    assert (
        contract.validate_env(contract.EnvFile(valid_values() | {field: str(key)}))
        == []
    )
    assert field in contract.NACOS_FORBIDDEN_KEYS
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (tmp_path / "identity-service.yaml").write_text(
        f"{field}: secret.json\n", encoding="utf-8"
    )
    assert contract.validate_nacos_source(tmp_path)
    assert contract.validate_env(
        contract.EnvFile(valid_values() | {"IDENTITY_SIGNING_JWK": "private"})
    )


def test_bootstrap_secret_reference_is_only_an_environment_binding(tmp_path):
    key = tmp_path / "bootstrap.json"
    key.write_text('{"test_only": true}', encoding="utf-8")
    field = "IDENTITY_BOOTSTRAP_SECRET_FILE"
    assert (
        contract.validate_env(contract.EnvFile(valid_values() | {field: str(key)}))
        == []
    )
    assert field in contract.NACOS_FORBIDDEN_KEYS
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (tmp_path / "identity-service.yaml").write_text(
        f"{field}: forbidden.json\n", encoding="utf-8"
    )
    assert contract.validate_nacos_source(tmp_path)


def test_runtime_database_binding_uses_secret_reference_and_never_nacos(tmp_path):
    password = tmp_path / "runtime.password"
    password.write_text("database-test-only", encoding="utf-8")
    fields = {
        "DB_NAME": "sample_local_identity",
        "DB_USER": "sample_local_identity_runtime",
        "DB_PASSWORD_SECRET_FILE": str(password),
    }
    assert contract.validate_env(contract.EnvFile(valid_values() | fields)) == []
    assert set(fields) <= contract.NACOS_FORBIDDEN_KEYS
    assert contract.validate_env(
        contract.EnvFile(valid_values() | {"DB_PASSWORD": "database-test-only"})
    )
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    for name in fields:
        (tmp_path / "identity-service.yaml").write_text(
            f"{name}: forbidden\n", encoding="utf-8"
        )
        assert contract.validate_nacos_source(tmp_path)


def test_resource_introspection_binding_is_declared_and_never_nacos(tmp_path):
    secret = tmp_path / "resource-client.password"
    secret.write_text("client-test-only", encoding="utf-8")
    fields = {
        "RESOURCE_AUDIENCE": "document-service",
        "INTROSPECTION_CLIENT_ID": "reader:document",
        "INTROSPECTION_CLIENT_SECRET_FILE": str(secret),
    }
    assert contract.validate_env(contract.EnvFile(valid_values() | fields)) == []
    assert set(fields) <= contract.NACOS_FORBIDDEN_KEYS
    assert contract.validate_env(
        contract.EnvFile(
            valid_values() | {"INTROSPECTION_CLIENT_SECRET": "client-test-only"}
        )
    )
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    for key in fields:
        (tmp_path / "identity-service.yaml").write_text(
            f"{key}: forbidden\n", encoding="utf-8"
        )
        assert contract.validate_nacos_source(tmp_path)


@pytest.mark.parametrize(
    "update",
    [
        {"PG_PORT": "bad"},
        {"PG_PORT": "65536"},
        {"REDIS_PORT": "0"},
        {"PROJECT_CODE": "../other"},
        {"NACOS_NAMESPACE": "other-prod"},
        {"PUBLIC_ORIGIN": "http://example.com"},
        {"APP_ENV": "production"},
        {"PG_MODE": "auto"},
        {"NACOS_AUTH_TOKEN": "<generated>"},
        {"POSTGRES_ADMIN_PASSWORD_SECRET_FILE": "missing-file"},
    ],
)
def test_wrong_environment_rejected(update):
    values = valid_values() | update
    assert contract.validate_env(contract.EnvFile(values))


def test_production_requires_https_and_explicit_modes():
    values = valid_values() | {"APP_ENV": "prod", "NACOS_NAMESPACE": "sample-prod"}
    assert contract.validate_env(contract.EnvFile(values))
    values.update(
        PUBLIC_ORIGIN="https://sample.example",
        IDENTITY_INTERNAL_ORIGIN="https://identity.internal",
        NACOS_CONSOLE_ADDR="https://nacos.internal",
        PG_MODE="external",
        REDIS_MODE="external",
        NACOS_MODE="external",
        RABBIT_MODE="external",
    )
    assert contract.validate_env(contract.EnvFile(values)) == []
    values["POSTGRES_ADMIN_PASSWORD"] = "literal-secret"
    assert any(
        "秘密文件引用" in issue
        for issue in contract.validate_env(contract.EnvFile(values))
    )


def test_duplicate_environment_field_rejected(tmp_path):
    path = tmp_path / "environment.env"
    path.write_text("APP_ENV=local\nAPP_ENV=prod\n", encoding="utf-8")
    with pytest.raises(ValueError, match="重复"):
        contract.parse_env_file(path)


def test_secret_reference_resolves_relative_to_environment(tmp_path):
    secret = tmp_path / "password"
    secret.write_text("private-value", encoding="utf-8")
    path = tmp_path / "environment.env"
    path.write_text(
        "APP_ENV=prod\nPOSTGRES_ADMIN_PASSWORD_SECRET_FILE=password\n", encoding="utf-8"
    )
    assert (
        contract.secret_value(
            contract.parse_env_file(path).values, "POSTGRES_ADMIN_PASSWORD"
        )
        == "private-value"
    )


@pytest.mark.parametrize(
    "service", ["api-gateway.yaml", "agent-service.yaml", "identity-service.yaml"]
)
def test_override_guard_independent_of_filename_order(tmp_path, service):
    (tmp_path / "application-shared.yaml").write_text(
        "http:\n  response_budget_seconds: 10\n", encoding="utf-8"
    )
    (tmp_path / service).write_text(
        "http:\n  response_budget_seconds: 100\n", encoding="utf-8"
    )
    assert any(
        "allow_override" in issue for issue in contract.validate_nacos_source(tmp_path)
    )


def test_explicit_nested_override_allowed(tmp_path):
    (tmp_path / "application-shared.yaml").write_text(
        "allow_override: [http.response_budget_seconds]\nhttp:\n  response_budget_seconds: 10\n",
        encoding="utf-8",
    )
    (tmp_path / "api-gateway.yaml").write_text(
        "http:\n  response_budget_seconds: 5\n", encoding="utf-8"
    )
    assert contract.validate_nacos_source(tmp_path) == []


@pytest.mark.parametrize(
    "field", ["client_secret", "model.api_key", "pg_host", "unknown_option"]
)
def test_unknown_or_secret_runtime_field_rejected(tmp_path, field):
    (tmp_path / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    (tmp_path / "api-gateway.yaml").write_text(f"{field}: private\n", encoding="utf-8")
    assert contract.validate_nacos_source(tmp_path)


def test_shared_config_required(tmp_path):
    assert contract.validate_nacos_source(tmp_path)


def test_resolved_pg_names_cannot_be_truncated():
    resource = {"name_binding": {"database": "a" * 63}, "roles": {"owner": "owner"}}
    with pytest.raises(ValueError, match="63"):
        contract.resolve_pg_resource(
            resource, {"PROJECT_CODE": "sample", "APP_ENV": "local"}
        )


def test_external_pg_names_separate_environments():
    resource = {
        "name_binding": {"database": "identity"},
        "roles": {"owner": "identity_owner"},
    }
    test = contract.resolve_pg_resource(
        resource, {"PROJECT_CODE": "sample", "APP_ENV": "test"}
    )
    prod = contract.resolve_pg_resource(
        resource, {"PROJECT_CODE": "sample", "APP_ENV": "prod"}
    )
    assert test["database"] == "sample_test_identity"
    assert prod["database"] == "sample_prod_identity"
    assert test["owner"] != prod["owner"]


@pytest.mark.parametrize("status,body", [(403, {}), (200, {"code": 403, "data": None})])
def test_remote_query_failure_cannot_be_treated_as_absent(
    tmp_path, monkeypatch, status, body
):
    import argparse

    import httpx

    from scripts.config import publish

    values = valid_values() | {
        "NACOS_PUBLISH_USER": "publisher",
        "NACOS_PUBLISH_PASSWORD": "dummy",
    }
    env_file = tmp_path / "environment.env"
    env_file.write_text(
        "".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8"
    )
    source = tmp_path / "source"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8"
    )
    writes = []

    def handle(request):
        if request.method != "GET":
            writes.append(request.method)
        return httpx.Response(status, json=body)

    real_client = httpx.Client
    monkeypatch.setattr(publish, "_login", lambda *args: "dummy-token")
    monkeypatch.setattr(
        publish.httpx,
        "Client",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs),
    )
    assert (
        publish.publish(
            argparse.Namespace(
                env_file=str(env_file), source=str(source), dry_run=False
            )
        )
        == 1
    )
    assert writes == []


@pytest.mark.parametrize(
    "proxy", ["0.0.0.0/0", "::/0", "name.example", "10.0.0.1/33", "10.0.0.1,,10.0.0.2"]
)
def test_trusted_proxy_binding_rejects_unbounded_or_invalid_sources(proxy):
    assert contract.validate_env(
        contract.EnvFile(valid_values() | {"TRUSTED_PROXY_CIDRS": proxy})
    )


def test_trusted_proxy_binding_is_optional_and_accepts_exact_networks():
    assert (
        contract.validate_env(
            contract.EnvFile(
                valid_values() | {"TRUSTED_PROXY_CIDRS": "127.0.0.1/32,2001:db8::/64"}
            )
        )
        == []
    )
