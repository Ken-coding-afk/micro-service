"""工作区生成、作用域和失败边界；不启动 Docker。"""

import argparse
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import generation  # noqa: E402
from build import business_dockerfile, copy_inputs  # noqa: E402
from common import read_yaml, service_directory, write  # noqa: E402
from create import create  # noqa: E402
from local import generated, prepare_environment  # noqa: E402


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
    monkeypatch.setattr(generation, "run", lambda *args, **kwargs: None)
    generation.generate(options(tmp_path))
    return tmp_path


def test_independent_routes_permissions_and_source_dependencies(workspace):
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
    assert "../../vendor/micro-service/pom.xml" in pom
    assert (
        workspace
        / "services/equipment-service/src/main/java/com/acme/equipment/SampleApplication.java"
    ).is_file()
    py = (workspace / "services/inspection-service/pyproject.toml").read_text()
    assert 'path = "../../vendor/micro-service/python-sdk"' in py
    assert 'inspection-service-api = "inspection.main:main"' in py
    assert not (workspace / "tools/infra/init.py").exists()
    assert not (workspace / "tools/config/publish.py").exists()
    assert 'value="equipment-service"' in (workspace / "web/index.html").read_text(encoding="utf-8")
    assert "${service}/items" in (workspace / "web/sample.js").read_text(encoding="utf-8")
    from common import load_tool

    assert (
        load_tool("tools/config/contract.py").validate_nacos_source(workspace / "config/nacos")
        == []
    )


def test_second_same_language_service_is_not_same_resource(workspace):
    generation.service_files(workspace, "java", "report-service", "com.acme.report", None)
    generation.register(workspace, "java", "report-service", "com.acme.report", 49000, None)
    value = read_yaml(workspace / "services/report-service/src/main/resources/permissions.yaml")
    assert value["permissions"][0]["code"] == "report-service-item:read"
    assert (
        read_yaml(workspace / "config/development.yaml")["services"]["report-service"]["peer"]
        is None
    )
    with pytest.raises(ValueError, match="冲突"):
        generation.register(workspace, "java", "report-service", "com.acme.report", 49000, None)


@pytest.mark.parametrize("name", ["../escape", "Identity", "identity-service", "nul", "a/../b"])
def test_bad_names_rejected(name):
    with pytest.raises(ValueError):
        generation.validate_name(name)


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
    compose = read_yaml(workspace / "deploy/business.compose.yaml")
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


def test_external_local_init_rejected_before_container_calls(workspace, monkeypatch):
    write(
        workspace / ".local/environment.env",
        "APP_ENV=local\nPROJECT_CODE=equipment-platform\nPG_MODE=external\nREDIS_MODE=self_hosted\nNACOS_MODE=self_hosted\n",
    )
    monkeypatch.setattr("socket.getaddrinfo", lambda *args: [])
    with pytest.raises(ValueError, match="external"):
        prepare_environment(
            workspace, read_yaml(workspace / "config/development.yaml"), "127.0.0.1"
        )


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


def test_new_service_is_not_silently_bootstrapped(workspace, monkeypatch):
    import local

    generation.service_files(workspace, "python", "scoring-service", "scoring", None)
    generation.register(workspace, "python", "scoring-service", "scoring", 49001, None)
    monkeypatch.setattr(local, "base_images", lambda *args: pytest.fail("must stop before Docker"))
    with pytest.raises(ValueError, match="新增服务"):
        local.initialize(workspace, None)


def test_invalid_config_stops_before_environment_or_docker(workspace, monkeypatch):
    import local

    write(workspace / "config/nacos/api-gateway.yaml", "unregistered_setting: true\n")
    monkeypatch.setattr(
        local, "prepare_environment", lambda *args: pytest.fail("must validate configuration first")
    )
    with pytest.raises(ValueError, match="项目配置未通过"):
        local.initialize(workspace, argparse.Namespace(dev_host=None))


def test_legacy_project_packaging_entry_is_retired():

    from common import FRAMEWORK

    assert not (FRAMEWORK / "tools/deploy/package_runtime.py").exists()


def test_deployment_bundle_has_migrations_but_no_source_or_secrets(workspace, monkeypatch):
    import json

    import build
    from common import write_yaml

    config = read_yaml(workspace / "config/development.yaml")
    identity = {"project_commit": "a" * 40, "framework_commit": "b" * 40, "dirty": False}
    business = read_yaml(workspace / "deploy/business.compose.yaml")
    business["volumes"] = {"project-data": {}}
    business["networks"] = {"project-net": {"driver": "bridge"}}
    business["x-project"] = {"owner": "business-team"}
    business["services"]["equipment-service"]["volumes"].append("project-data:/data")
    business["services"]["equipment-service"]["networks"].append("project-net")
    write_yaml(workspace / "deploy/business.compose.yaml", business)
    names = ["identity-service", "api-gateway", "nginx", *config["services"]]
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
    monkeypatch.setattr(build, "run", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        build.subprocess, "check_output", lambda *args, **kwargs: "sha256:" + "d" * 64
    )
    output = workspace / "dist/test"
    args = argparse.Namespace(
        images=workspace / ".local/images.json",
        env_file=workspace / ".local/production.env",
        output=output,
        release="0.1.0",
        web_root=None,
    )
    build.bundle(workspace, args, config, identity)
    release = read_yaml(output / "deploy/release.yaml")
    assert [m["target"] for m in release["migrations"]] == [
        "identity-db",
        "equipment-service-db",
        "inspection-service-db",
    ]
    assert not (output / ".local").exists()
    assert not (output / "vendor").exists()
    assert not (output / "services").exists()
    assert (output / "artifacts/inspection-service-migrations/env.py").is_file()
    assert (output / "tools/deploy/verify_release.py").is_file()
    assert all(name in read_yaml(output / "deploy/compose.yaml")["services"] for name in names)
    bundled = read_yaml(output / "deploy/compose.yaml")
    assert bundled["volumes"] == business["volumes"]
    assert bundled["networks"]["project-net"] == business["networks"]["project-net"]
    assert bundled["x-project"] == business["x-project"]
    assert "application" in bundled["networks"]
    assert "project-data:/data" in bundled["services"]["equipment-service"]["volumes"]
    assert "project-net" in bundled["services"]["equipment-service"]["networks"]

    business["networks"]["application"] = {"external": False}
    write_yaml(workspace / "deploy/business.compose.yaml", business)
    args.output = workspace / "dist/conflict"
    with pytest.raises(ValueError, match="Compose 顶层声明冲突：networks.application"):
        build.bundle(workspace, args, config, identity)


@pytest.mark.parametrize("language", ["java", "python"])
def test_add_service_preserves_existing_compose_settings(workspace, language):
    import cli
    from common import write_yaml

    path = workspace / "deploy/business.compose.yaml"
    compose = read_yaml(path)
    compose["services"]["equipment-service"].update(
        {
            "environment": {"BUSINESS_MODE": "project-owned"},
            "mem_limit": "768m",
            "volumes": ["project-data:/data"],
            "labels": {"team": "project-owner"},
        }
    )
    compose["networks"] = {"project-net": {"external": True}}
    compose["volumes"] = {"project-data": {}}
    compose["x-project"] = {"owner": "project-team"}
    write_yaml(path, compose)
    cli.add_service(
        workspace,
        argparse.Namespace(
            language=language,
            name="report-service",
            package="com.acme.report" if language == "java" else "report",
            port=49100,
        ),
    )
    after = read_yaml(path)
    assert after["services"].pop("report-service")
    assert after == compose


def test_compose_name_conflict_does_not_change_registration(workspace):
    import cli
    from common import write_yaml

    compose_path = workspace / "deploy/business.compose.yaml"
    compose = read_yaml(compose_path)
    compose["services"]["scoring-service"] = {"image": "project-owned"}
    write_yaml(compose_path, compose)
    controlled = [
        "config/development.yaml",
        "config/nacos/api-gateway.yaml",
        "deploy/business.compose.yaml",
        "pom.xml",
    ]
    before = {name: (workspace / name).read_bytes() for name in controlled}
    with pytest.raises(ValueError, match="Compose 服务已存在"):
        cli.add_service(
            workspace,
            argparse.Namespace(
                language="python", name="scoring-service", package="scoring", port=49100
            ),
        )
    assert {name: (workspace / name).read_bytes() for name in controlled} == before
    assert not (workspace / "services/scoring-service").exists()


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
    import local

    class StopBeforeInfrastructure(Exception):
        pass

    calls = []
    monkeypatch.setattr(
        local, "prepare_environment", lambda *a: (workspace / ".local/environment.env", "127.0.0.1")
    )
    monkeypatch.setattr(local, "run", lambda *a, **k: None)
    monkeypatch.setattr(local, "base_images", lambda *a: {})

    def stop(root, path, *args):
        calls.append((root, path, args))
        raise StopBeforeInfrastructure

    monkeypatch.setattr(local, "tool", stop)
    with pytest.raises(StopBeforeInfrastructure):
        local.initialize(workspace, argparse.Namespace(dev_host=None))
    assert calls == [
        (
            workspace,
            "tools/infra/init.py",
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
