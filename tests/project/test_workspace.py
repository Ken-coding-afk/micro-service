"""工作区生成、作用域和失败边界；不启动 Docker。"""

import argparse
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import generation  # noqa: E402
import package as packaging  # noqa: E402
from build import business_dockerfile, copy_inputs  # noqa: E402
from catalog import project_config
from common import read_yaml, service_directory, write  # noqa: E402
from create import create  # noqa: E402
from init import generated, prepare_environment  # noqa: E402


def options(root):
    return argparse.Namespace(
        root=root,
        project="equipment-platform",
        java_service="equipment-service",
        java_package="com.acme.equipment",
        python_service="inspection-service",
        python_package="inspection",
        port_offset=200,
    )


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    generation.generate(options(tmp_path))
    return tmp_path


def test_independent_routes_permissions_and_source_dependencies(workspace):
    assert not (workspace / "scripts/instructions.py").exists()
    assert not (workspace / "scripts/generation.py").exists()
    assert not (workspace / "tests/fixtures/federation").exists()
    assert (workspace / "tests/acceptance/smoke.cjs").is_file()
    config = read_yaml(workspace / "config/development.yaml")
    assert set(config["services"]) == {"equipment-service", "inspection-service"}
    assert config["services"]["equipment-service"]["port"] == 48282
    route = read_yaml(workspace / "config/nacos/api-gateway.yaml")["spring"]["cloud"]["gateway"][
        "server"
    ]["webflux"]["routes"]
    assert [r["predicates"] for r in route] == [
        ["Path=/api/v1/equipment-service/**"],
        ["Path=/api/v1/inspection-service/**"],
    ]
    for name, package, language in [
        ("equipment-service", "com/acme/equipment", "java"),
        ("inspection-service", "inspection", "python"),
    ]:
        folder = workspace / "services" / name
        manifest = folder / (
            "src/main/resources/permissions.yaml"
            if language == "java"
            else f"src/{package}/resources/permissions.yaml"
        )
        value = read_yaml(manifest)
        assert value["service"] == name
        assert {p["code"] for p in value["permissions"]} == {
            name + "-item:read",
            name + "-item:create",
        }
        assert all(p["resource_code"] == name + "-item" for p in value["permissions"])
        assert all(p["oauth_scope"].startswith(name + ".items.") for p in value["permissions"])
    pom = (workspace / "services/equipment-service/pom.xml").read_text()
    assert "../../pom.xml" in pom
    assert (
        workspace
        / "services/equipment-service/src/main/java/com/acme/equipment/SampleApplication.java"
    ).is_file()
    py = (workspace / "services/inspection-service/pyproject.toml").read_text()
    assert 'path = "../../sdk/python"' in py
    assert 'inspection-service-api = "inspection.main:main"' in py
    assert (workspace / "scripts/infra/init.py").exists()
    assert (workspace / "scripts/config/publish.py").exists()
    assert 'value="equipment-service"' in (workspace / "web/index.html").read_text(encoding="utf-8")
    assert "${service}/items" in (workspace / "web/sample.js").read_text(encoding="utf-8")
    from common import load_tool

    assert (
        load_tool("scripts/config/contract.py").validate_nacos_source(workspace / "config/nacos")
        == []
    )


def test_second_same_language_service_is_not_same_resource(workspace):
    generation.service_files(workspace, "java", "report-service", "com.acme.report", None)
    generation.register(workspace, "java", "report-service", "com.acme.report", 49000, None)
    value = read_yaml(workspace / "services/report-service/src/main/resources/permissions.yaml")
    assert value["permissions"][0]["code"] == "report-service-item:read"
    assert project_config(workspace)["services"]["report-service"]["peer"] is None
    with pytest.raises(ValueError, match="冲突"):
        generation.register(workspace, "java", "report-service", "com.acme.report", 49000, None)


@pytest.mark.parametrize("name", ["../escape", "Identity", "identity-service", "nul", "a/../b"])
def test_bad_names_rejected(name):
    with pytest.raises(ValueError):
        generation.validate_name(name)


@pytest.mark.parametrize("dev_host", ["host.docker.internal", "192.168.44.7", "127.0.0.1"])
def test_current_bindings_preserve_privilege_and_secret_boundaries(workspace, dev_host):
    import init as local
    from catalog import database_resource

    config = project_config(workspace)
    env = {
        "APP_ENV": "local",
        "PROJECT_CODE": "equipment-platform",
        "PUBLIC_ORIGIN": "http://localhost:18280",
        "PG_HOST": "127.0.0.1",
        "PG_PORT": "15432",
        "PG_MODE": "self_hosted",
        "NACOS_MODE": "self_hosted",
        "NACOS_SERVER_ADDR": "127.0.0.1:8848",
        "NACOS_NAMESPACE": "equipment-platform-local",
        "NACOS_RUNTIME_USER": "runtime",
        "NACOS_RUNTIME_PASSWORD": "fixture-nacos",
    }
    runtime = workspace / ".local/runtime"
    local.generated(workspace, runtime / "api-gateway-secrets/bff.json", "{}")
    local.generated(workspace, runtime / "api-gateway.env", "APP_ENV=local\n")
    for record in config["services"].values():
        resource = database_resource(workspace, record)["resource_id"]
        write(workspace / f".local/secrets/{resource}-runtime.password", "fixture-db")
    local.bind_business(workspace, config, env, dev_host)
    for name in config["services"]:
        values = local.env_values(runtime / f"{name}.env")
        host_values = local.env_values(runtime / f"{name}-host.env")
        assert set(host_values["NO_PROXY"].split(",")) == {"localhost", "127.0.0.1", dev_host}
        assert "*" not in host_values["NO_PROXY"]
        assert "NO_PROXY" not in values
        assert not any(k.startswith(("IDENTITY_", "REDIS_")) for k in values)
        assert values["DB_USER"].endswith("_runtime")
        assert {p.name for p in (runtime / f"{name}-secrets").iterdir()} == {
            "database.password",
            "introspection.password",
            "nacos-runtime.password",
        }
    before = {p: p.read_bytes() for p in runtime.rglob("*") if p.is_file()}
    local.bind_business(workspace, config, env, dev_host)
    assert {p: p.read_bytes() for p in runtime.rglob("*") if p.is_file()} == before
    secret = runtime / "equipment-service-secrets/introspection.password"
    secret.write_text("rotated", encoding="utf-8")
    with pytest.raises(ValueError, match="覆盖"):
        local.bind_business(workspace, config, env, dev_host)
    assert secret.read_text() == "rotated"


def test_current_bindings_reject_production_before_writes(workspace):
    import init as local

    with pytest.raises(ValueError, match="local"):
        local.bind_business(workspace, project_config(workspace), {"APP_ENV": "prod"}, "host")
    assert not (workspace / ".local").exists()


def test_nonempty_create_never_touches_target(tmp_path):
    marker = tmp_path / "mine.txt"
    marker.write_text("preserve")
    with pytest.raises(ValueError):
        create(argparse.Namespace(output=tmp_path))
    assert marker.read_text() == "preserve"


def test_changed_generated_input_is_preserved(tmp_path):
    output = tmp_path / ".local/runtime/app.env"
    generated(tmp_path, output, "A=first\n")
    generated(tmp_path, output, "A=second\n")
    output.write_text("A=human\n")
    with pytest.raises(ValueError, match="人工修改"):
        generated(tmp_path, output, "A=third\n")
    assert output.read_text() == "A=human\n"


def test_source_scope_and_secrets_not_copied(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    write(source / ".local/password", "secret")
    write(source / "src/app.py", "pass")
    copy_inputs(source, tmp_path / "context")
    assert not (tmp_path / "context/.local").exists()
    write(source / "leak.password", "secret")
    with pytest.raises(ValueError, match="秘密"):
        copy_inputs(source, tmp_path / "rejected")
    with pytest.raises(ValueError, match="services"):
        service_directory(source, {"directory": "../context"})


def test_python_image_noneditable_without_host_wheels():
    text = business_dockerfile("compute-service", {"language": "python", "package": "compute"})
    assert "--no-editable" in text and "uv lock --check" in text
    assert "wheels" not in text
    assert "COPY --from=build" in text


def test_business_uv_does_not_replace_tools_environment(tmp_path, monkeypatch):
    import common

    monkeypatch.setenv("UV_PROJECT_ENVIRONMENT", str(tmp_path / "tools-venv"))
    captured = {}
    monkeypatch.setattr(common.subprocess, "run", lambda *args, **kwargs: captured.update(kwargs))
    common.run(["uv", "sync", "--frozen"], cwd=tmp_path)
    assert "UV_PROJECT_ENVIRONMENT" not in captured["env"]


def test_deployment_lists_new_services_without_local_runtime(workspace):
    generation.service_files(workspace, "python", "scoring-service", "scoring", None)
    generation.register(workspace, "python", "scoring-service", "scoring", 49001, None)
    compose = read_yaml(workspace / "deploy/generated.services.yaml")
    assert set(compose["services"]) == {
        "equipment-service",
        "inspection-service",
        "scoring-service",
    }
    assert (
        compose["services"]["scoring-service"]["env_file"] == "${RUNTIME_DIR}/scoring-service.env"
    )
    assert not (workspace / ".local/runtime").exists()
    for service in compose["services"].values():
        assert "SERVICE_BIND_PORT" in " ".join(service["healthcheck"]["test"])


def test_external_local_init_preserves_supplied_environment(workspace, monkeypatch):
    content = "APP_ENV=local\nPROJECT_CODE=equipment-platform\nPG_MODE=external\nREDIS_MODE=self_hosted\nNACOS_MODE=self_hosted\n"
    write(workspace / ".local/environment.env", content)
    monkeypatch.setattr("socket.getaddrinfo", lambda *args: [])
    path, host = prepare_environment(workspace, project_config(workspace), "127.0.0.1")
    assert path.read_text(encoding="utf-8") == content
    assert host == "127.0.0.1"


def test_initialization_offsets_optional_component_endpoints_and_ports(workspace, monkeypatch):
    from init import env_values

    monkeypatch.setattr("socket.getaddrinfo", lambda *args: [])
    path, _ = prepare_environment(workspace, project_config(workspace), "127.0.0.1")
    values = env_values(path)
    assert values["RABBIT_PORT"] == values["RABBIT_PUBLISHED_PORT"] == "5872"
    assert values["RABBIT_MGMT_PORT"] == values["RABBIT_MANAGEMENT_PUBLISHED_PORT"] == "15872"
    assert values["GRAFANA_PUBLISHED_PORT"] == "13200"
    before = path.read_bytes()
    prepare_environment(workspace, project_config(workspace), "127.0.0.1")
    assert path.read_bytes() == before


def test_repeat_init_preserves_explicit_host(workspace, monkeypatch):
    write(workspace / ".local/dev-host.txt", "192.168.1.201")
    write(
        workspace / ".local/environment.env",
        "APP_ENV=local\nPROJECT_CODE=equipment-platform\nPG_MODE=self_hosted\nREDIS_MODE=self_hosted\nNACOS_MODE=self_hosted\n",
    )
    monkeypatch.setattr("socket.getaddrinfo", lambda *args: [])
    config = read_yaml(workspace / "config/development.yaml")
    assert prepare_environment(workspace, config, None)[1] == "192.168.1.201"
    with pytest.raises(ValueError, match="已固定"):
        prepare_environment(workspace, config, "192.168.1.202")


@pytest.mark.parametrize("enabled,occupied,blocked", [
    ("", 13200, False),  # Grafana 未启用。
    ("", 5872, False),  # RabbitMQ 未启用。
    ("", 48283, False),  # 未选的默认 Python 服务。
    ("", 16579, False),  # external Redis 不占本机发布端口。
    ("", 49501, True),  # 已选业务使用实际自定义端口，不再加 offset。
    ("", 15632, True),  # 已选自建 PG。
    ("COMPOSE_PROFILES=monitoring\n", 13200, True),
    ("COMPOSE_PROFILES=monitoring\n", 50501, True),  # 实际业务指标端口。
    ("RABBIT_HOST=127.0.0.1\n", 5872, True),
])
def test_initialization_checks_only_enabled_components_and_selected_actual_ports(
    tmp_path, monkeypatch, enabled, occupied, blocked
):
    import init as local

    write(tmp_path / "config/environments/local.env.example",
          "PROJECT_CODE=fixture\nAPP_ENV=local\nPG_MODE=self_hosted\nREDIS_MODE=external\n"
          "NACOS_MODE=external\nRABBIT_MODE=self_hosted\n" + enabled)
    config = {"local": {"project": "fixture", "port_offset": 200},
              "services": {"selected-service": {"port": 49501}}}
    checked = []

    class Probe:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def bind(self, address):
            checked.append(address[1])
            if address[1] == occupied:
                raise OSError("fixture occupied port")

    monkeypatch.setattr(local.socket, "socket", Probe)
    monkeypatch.setattr(local.socket, "getaddrinfo", lambda *args: [])
    if blocked:
        with pytest.raises(ValueError, match=str(occupied)):
            local.prepare_environment(tmp_path, config, "127.0.0.1")
        assert not (tmp_path / ".local/environment.env").exists()
    else:
        local.prepare_environment(tmp_path, config, "127.0.0.1")
        assert set(checked) == {18280, 18281, 15632, 49501}


def test_gateway_publication_survives_interruption_and_failed_restart(tmp_path, monkeypatch):
    import json
    import init as local

    # 已在 Nacos 发布，但初始化在迁移阶段失败；旧网关仍应用旧摘要。
    applied = tmp_path / ".local/runtime/gateway-applied-config.json"
    old = {"server/project/local/ns/api-gateway.yaml": "old"}
    new = {"server/project/local/ns/api-gateway.yaml": "new"}
    write(applied, json.dumps(old))
    write(tmp_path / ".local/publish-state.json", json.dumps(new))
    write(tmp_path / "config/nacos/api-gateway.yaml", "fixture: true")
    import catalog
    monkeypatch.setattr(catalog, "nacos_content", lambda root, path, selected: path.read_text())
    monkeypatch.setattr(catalog, "nacos_files", lambda *args: [tmp_path / "config/nacos/api-gateway.yaml"])
    monkeypatch.setattr(local, "tool", lambda *args: None)  # 重跑发布，远端摘要未再变化。
    snapshot = local.publish_nacos(tmp_path, {"selected": []}, tmp_path / ".local/environment.env")
    assert snapshot == new
    calls = []
    failures = [True]

    def compose(root, *args):
        calls.append(args)
        if "--force-recreate" in args and failures[0]:
            raise RuntimeError("fixture healthcheck failed")

    monkeypatch.setattr(local, "compose", compose)
    with pytest.raises(RuntimeError, match="healthcheck"):
        local.start_gateway(tmp_path, snapshot)
    assert json.loads(applied.read_text()) == old
    failures[0] = False
    local.start_gateway(tmp_path, snapshot)
    assert json.loads(applied.read_text()) == new
    assert sum("--force-recreate" in args for args in calls) == 2
    local.start_gateway(tmp_path, snapshot)
    assert sum("--force-recreate" in args for args in calls) == 2
    assert calls[-1] == ("up", "-d", "--wait", "api-gateway", "nginx")


def test_gateway_without_applied_record_restarts_once_and_rejects_missing_publication(tmp_path, monkeypatch):
    import init as local

    calls = []
    monkeypatch.setattr(local, "compose", lambda root, *args: calls.append(args))
    with pytest.raises(ValueError, match="摘要"):
        local.start_gateway(tmp_path, {})
    assert calls == []
    snapshot = {"fixture/api-gateway.yaml": "known"}
    local.start_gateway(tmp_path, snapshot)
    local.start_gateway(tmp_path, snapshot)
    assert sum("--force-recreate" in args for args in calls) == 1


def test_new_service_selection_does_not_reset_existing_identity(workspace, monkeypatch):
    import init as local

    generation.service_files(workspace, "python", "scoring-service", "scoring", None)
    generation.register(workspace, "python", "scoring-service", "scoring", 49001, None)
    captured = {}

    class BeforeRuntime(Exception):
        pass

    def capture(root, config, host):
        captured.update(config)
        raise BeforeRuntime

    monkeypatch.setattr(local, "prepare_environment", capture)
    with pytest.raises(BeforeRuntime):
        local.initialize(
            workspace,
            argparse.Namespace(services="scoring-service", with_demo=False, dev_host=None),
        )
    assert set(captured["services"]) == {"scoring-service"}
    assert not (workspace / ".local/runtime/identity-bootstrap-secrets/local.json").exists()


def test_invalid_config_stops_before_environment_or_docker(workspace, monkeypatch):
    import init as local

    write(workspace / "config/nacos/api-gateway.yaml", "unregistered_setting: true\n")
    monkeypatch.setattr(
        local, "prepare_environment", lambda *args: pytest.fail("must validate configuration first")
    )
    with pytest.raises(ValueError, match="项目配置未通过"):
        local.initialize(workspace, argparse.Namespace(dev_host=None))


def test_legacy_project_packaging_entry_is_retired():

    from common import FRAMEWORK

    assert not (FRAMEWORK / "scripts/deploy/package_runtime.py").exists()


def test_deployment_bundle_has_migrations_but_no_source_or_secrets(workspace, monkeypatch):
    import json

    from common import write_yaml

    config = project_config(workspace)
    identity = {"project_commit": "a" * 40, "framework_commit": "b" * 40, "dirty": False}
    business = read_yaml(workspace / "deploy/generated.services.yaml")
    business["volumes"] = {"project-data": {}}
    business["networks"] = {"project-net": {"driver": "bridge"}}
    business["x-project"] = {"owner": "business-team"}
    business["services"]["equipment-service"]["volumes"].append("project-data:/data")
    business["services"]["equipment-service"]["networks"].append("project-net")
    write_yaml(workspace / "deploy/compose.override.yaml", business)
    names = [
        "identity-service",
        "api-gateway",
        "nginx",
        *config["services"],
        "inspection-service-migration",
    ]
    images = {name: "registry.invalid/" + name + "@sha256:" + "c" * 64 for name in names}
    write(workspace / ".local/images.json", json.dumps(images))
    write(
        workspace / ".local/build-record.json",
        json.dumps({"source": identity, "images": {name: "sha256:" + "d" * 64 for name in names}}),
    )
    write(workspace / ".local/production.env", "PROJECT_CODE=equipment-platform\nAPP_ENV=test\n")
    write(workspace / ".local/private.password", "never-copy-this")
    write_yaml(
        workspace / "deploy/release.yaml",
        {
            "rollback_window_hours": 24,
            "compatible_previous_versions": [],
            "owner": "test-owner",
            "approval_reference": "test-confirmation",
        },
    )
    monkeypatch.setattr(packaging, "run", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        packaging.subprocess, "check_output", lambda *args, **kwargs: "sha256:" + "d" * 64
    )
    output = workspace / "dist/test"
    args = argparse.Namespace(
        images=workspace / ".local/images.json",
        env_file=workspace / ".local/production.env",
        output=output,
        release="0.1.0",
        web_root=None,
    )
    packaging.bundle(workspace, args, config, identity)
    release = read_yaml(output / "deploy/release.yaml")
    assert [m["target"] for m in release["migrations"]] == [
        "identity-db",
        "equipment-service-db",
        "inspection-service-db",
    ]
    assert not (output / ".local").exists()
    assert not (output / "vendor").exists()
    assert not (output / "services/equipment-service/src/main/java").exists()
    assert not (output / "services/inspection-service/src/inspection/main.py").exists()
    assert (output / "services/inspection-service/migrations/env.py").is_file()
    assert (output / "scripts/deploy/verify_release.py").is_file()
    assert all(
        name in read_yaml(output / "deploy/compose.yaml")["services"]
        for name in names
        if not name.endswith("-migration")
    )
    bundled = read_yaml(output / "deploy/compose.override.yaml")
    assert bundled == business
    assert "deploy/compose.override.yaml" in release["compose_files"]
    assert "application" in read_yaml(output / "deploy/compose.yaml")["networks"]


@pytest.mark.parametrize("language", ["java", "python"])
def test_add_service_preserves_existing_compose_settings(workspace, language):
    from common import write_yaml

    path = workspace / "deploy/compose.override.yaml"
    compose = {
        "services": {
            "equipment-service": {
                "environment": {"BUSINESS_MODE": "project-owned"},
                "mem_limit": "768m",
                "volumes": ["project-data:/data"],
                "labels": {"team": "project-owner"},
            }
        },
        "volumes": {"project-data": {}},
        "networks": {"project-net": {"external": True}},
    }
    write_yaml(path, compose)
    before = path.read_bytes()
    generation.service_files(
        workspace,
        language,
        "report-service",
        "com.acme.report" if language == "java" else "report",
        None,
    )
    generation.register(
        workspace,
        language,
        "report-service",
        "com.acme.report" if language == "java" else "report",
        49100,
        None,
    )
    assert path.read_bytes() == before
    assert "report-service" in read_yaml(workspace / "deploy/generated.services.yaml")["services"]


def test_name_conflict_does_not_change_registration(workspace):
    controlled = [
        "config/services.yaml",
        "config/development.yaml",
        "config/nacos/api-gateway.yaml",
        "deploy/generated.services.yaml",
        "pom.xml",
    ]
    before = {name: (workspace / name).read_bytes() for name in controlled}
    with pytest.raises(ValueError, match="冲突"):
        generation.register(workspace, "python", "inspection-service", "inspection", 49100, None)
    assert {name: (workspace / name).read_bytes() for name in controlled} == before


def test_created_nacos_configuration_only_contains_selected_services(workspace):
    assert {p.name for p in (workspace / "config/nacos").iterdir()} == {
        "api-gateway.yaml",
        "identity-service.yaml",
        "application-shared.yaml",
        "equipment-service.yaml",
        "equipment-service-sentinel-flow.json",
        "equipment-service-sentinel-degrade.json",
        "inspection-service.yaml",
    }


def test_initializer_passes_project_redis_template(workspace, monkeypatch):
    import init as local

    write(workspace / ".local/environment.env", "APP_ENV=local\n")

    class StopBeforeInfrastructure(Exception):
        pass

    calls = []
    monkeypatch.setattr(
        local, "prepare_environment", lambda *a: (workspace / ".local/environment.env", "127.0.0.1")
    )
    monkeypatch.setattr(local, "run", lambda *a, **k: None)
    monkeypatch.setattr(local, "base_images", lambda *a: {})
    monkeypatch.setattr(local, "env_values", lambda *a: {})
    monkeypatch.setattr(local, "build_service", lambda *a, **k: "sha256:" + "a" * 64)

    def stop(root, path, *args):
        calls.append((root, path, args))
        raise StopBeforeInfrastructure

    monkeypatch.setattr(local, "tool", stop)
    with pytest.raises(StopBeforeInfrastructure):
        local.initialize(workspace, argparse.Namespace(dev_host=None))
    assert calls == [
        (
            workspace,
            "scripts/infra/init.py",
            (
                "--env-file",
                workspace / ".local/environment.env",
                "--phase",
                "prepare-local",
                "--redis-template",
                workspace / "infra/redis/redis.conf",
            ),
        )
    ]


def test_maven_wrapper_is_published_as_executable():
    import subprocess

    from common import FRAMEWORK

    tracked = subprocess.check_output(
        ["git", "ls-files", "--stage", "mvnw"], cwd=FRAMEWORK, text=True
    )
    assert tracked.split()[0] == "100755"
    assert b"\r\n" not in (FRAMEWORK / "mvnw").read_bytes()


@pytest.mark.parametrize("monitoring", [False, True])
def test_initialization_orders_config_identity_catalog_then_business_migration(
    workspace, monkeypatch, monitoring
):
    from types import SimpleNamespace

    import init as local

    events = []
    env_path = workspace / ".local/environment.env"
    runtime = workspace / ".local/runtime"
    local.generated(workspace, runtime / "compose.env", "fixture")
    env = {
        "APP_ENV": "local",
        "PROJECT_CODE": "equipment-platform",
        "PG_MODE": "external",
        "PUBLIC_ORIGIN": "http://localhost:18280",
        "IDENTITY_ADMIN_PORT": "18281",
        "COMPOSE_PROFILES": "monitoring" if monitoring else "",
    }
    write(env_path, "".join(f"{key}={value}\n" for key, value in env.items()))
    monkeypatch.setattr(local, "prepare_environment", lambda *a: (env_path, "127.0.0.1"))
    monkeypatch.setattr(local, "env_values", lambda *a: dict(env))
    monkeypatch.setattr(local, "run", lambda *a, **k: None)
    monkeypatch.setattr(
        local, "base_images", lambda *a: {"identity-service": "identity", "api-gateway": "gateway"}
    )
    monkeypatch.setattr(local, "build_service", lambda *a, **k: "sha256:" + "a" * 64)
    def tool(root, name, *args):
        events.append((name, *map(str, args)))
        if name == "scripts/config/publish.py":
            write(root / ".local/publish-state.json", '{"fixture/api-gateway.yaml": "published"}')

    monkeypatch.setattr(local, "tool", tool)
    monkeypatch.setattr(
        local,
        "migrate",
        lambda root, env, resources, target, *args, **kw: events.append(("migrate", target)),
    )
    monkeypatch.setattr(local, "compose", lambda root, *args: events.append(("compose", *args)))

    def bind(root, config, *args):
        for name in config["services"]:
            write(runtime / f"identity-bootstrap-secrets/{name}.password", "fixture")

    monkeypatch.setattr(local, "bind_business", bind)
    original = local.load_tool

    def load(name):
        if name.endswith("sync_permissions.py"):
            return SimpleNamespace(publish=lambda *args: events.append(("permissions",)))
        module = original(name)
        if monitoring and name.endswith("prepare_local.py"):

            def changed(*args):
                events.append(("proxy-sync",))
                return True

            module.sync_local_monitoring_proxy = changed
        return module

    monkeypatch.setattr(local, "load_tool", load)
    local.initialize(
        workspace, argparse.Namespace(dev_host=None, services="inspection-service", with_demo=False)
    )

    def index(text):
        return next(i for i, event in enumerate(events) if text in event)

    assert index("scripts/config/publish.py") < index("identity-db")
    assert index("identity-db") < index("bootstrap-services-technical") < index("permissions")
    assert (
        index("permissions")
        < index("bootstrap-services-applications")
        < index("inspection-service-db")
    )
    assert not any("equipment-service-db" in event for event in events)
    assert not any("bootstrap-local" in event for event in events)
    assert any("http://127.0.0.1:18281/readyz" in event for event in events)
    if monitoring:
        syncs = [i for i, event in enumerate(events) if event == ("proxy-sync",)]
        gateway = events.index(("compose", "up", "-d", "--wait", "api-gateway", "nginx"))
        grafana = next(i for i, event in enumerate(events) if "grafana" in event)
        assert len(syncs) == 2 and syncs[0] < gateway < syncs[1] < grafana


def test_partial_build_reuses_core_and_rejects_changed_core_inputs(
    workspace, monkeypatch, tmp_path
):
    import json

    import build
    from common import write_yaml

    write_yaml(
        workspace / "deploy/release.yaml",
        {
            "owner": "test",
            "approval_reference": "fixture",
            "compatible_previous_versions": [],
            "rollback_window_hours": 24,
        },
    )
    fingerprint = build.base_fingerprint(workspace)
    baseline = workspace / ".local/previous-release.yaml"
    write_yaml(
        baseline,
        {
            "project": "equipment-platform",
            "source": {"base_fingerprint": fingerprint},
            "images": {
                name: "registry.invalid/" + name + "@sha256:" + "a" * 64
                for name in ("identity-service", "api-gateway", "nginx")
            },
        },
    )
    monkeypatch.setattr(
        build,
        "source_identity",
        lambda *a, **k: {
            "base_fingerprint": fingerprint,
            "project_commit": "a" * 40,
            "dirty": False,
        },
    )
    calls = []
    monkeypatch.setattr(build, "run", lambda *a, **k: None)
    monkeypatch.setattr(build.subprocess, "check_output", lambda *a, **k: "sha256:" + "a" * 64)
    monkeypatch.setattr(build, "base_images", lambda *a: pytest.fail("局部发布不能重建基础镜像"))
    monkeypatch.setattr(
        build,
        "build_service",
        lambda root, name, *a, **kw: (
            calls.append((name, kw.get("migration", False))) or "sha256:" + "b" * 64
        ),
    )
    args = argparse.Namespace(
        release="1.0",
        services="inspection-service",
        base_release=baseline,
        output=workspace / "dist/one",
    )
    build.build(workspace, args)
    assert calls == [("inspection-service", False), ("inspection-service", True)]
    assert (
        json.loads((args.output / "build-record.json").read_text(encoding="utf-8"))["complete"]
        is False
    )
    args.output = workspace / "dist/two"
    write(workspace / "sdk/java/changed.txt", "changed")
    assert build.base_fingerprint(workspace) != fingerprint
    fingerprint = "different"
    with pytest.raises(ValueError, match="基础源码"):
        build.build(workspace, args)
