"""独立源码、Git submodule 和 IDE 使用路径；不启动 Docker。"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import generation
from add_service import add_service
from catalog import nacos_content, nacos_files, project_config, services
from common import load_tool, write
from create_service import create_service
from ide import install


@pytest.fixture
def project(tmp_path, monkeypatch):
    root = tmp_path / "含空格 project"
    generation.generate(
        argparse.Namespace(
            root=root,
            project="equipment-platform",
            java_service="equipment-service",
            java_package="com.acme.equipment",
            python_service="inspection-service",
            python_package="inspection",
            port_offset=0,
        )
    )
    # 只在测试子进程允许本地 Git fixture；不修改全局协议策略。
    monkeypatch.setenv("GIT_ALLOW_PROTOCOL", "file")
    monkeypatch.setenv("GIT_CONFIG_COUNT", "2")
    monkeypatch.setenv("GIT_CONFIG_KEY_1", "core.longpaths")
    monkeypatch.setenv("GIT_CONFIG_VALUE_1", "true")
    monkeypatch.setenv("GIT_CONFIG_KEY_0", "core.hooksPath")
    monkeypatch.setenv("GIT_CONFIG_VALUE_0", str(tmp_path / "empty-hooks"))
    git(root, "init", "-b", "main")
    commit(root)
    return root


def git(root, *args):
    return subprocess.check_output(
        ["git", *args], cwd=root, text=True, encoding="utf-8", stderr=subprocess.PIPE
    ).strip()


def commit(root):
    git(root, "add", ".")
    git(
        root,
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "-qm",
        "fixture",
    )
    return git(root, "rev-parse", "HEAD")


def test_generated_project_has_one_source_and_independent_entries(project):
    assert not any(
        (project / path).exists()
        for path in (
            "template",
            "vendor",
            "manage.py",
            "scripts/create.py",
            "scripts/create_service.py",
            "scripts/generation.py",
            "services/sample-java-service",
            "services/sample-python-service",
        )
    )
    assert set(p.name for p in (project / "services").iterdir()) == {
        "identity-service",
        "api-gateway",
        "equipment-service",
        "inspection-service",
    }
    assert (project / "sdk/java/pom.xml").is_file()
    assert (project / "sdk/python/pyproject.toml").is_file()
    assert (project / "services/inspection-service/Dockerfile").is_file()
    workflow = yaml.safe_load(
        (project / ".github/workflows/checks.yml").read_text(encoding="utf-8")
    )
    assert "maintenance" not in workflow["jobs"]
    entries = workflow["jobs"]["python"]["strategy"]["matrix"]["include"]
    python_tests = next(
        row["tests"] for row in entries if row["module"] == "services/inspection-service"
    )
    for test in ("test_items.py", "test_business_config.py", "test_application.py"):
        assert test in python_tests
        assert (project / "services/inspection-service/tests" / test).is_file()
    assert not (project / "tests/acceptance/legacy").exists()
    assert (project / "tests/acceptance/smoke.cjs").is_file()
    assert (project / "docs/guides/05-工程检查与监控指南.md").is_file()
    for guide in (project / "docs/guides").glob("*.md"):
        for link in re.findall(r"\]\(([^)]+\.md(?:#[^)]*)?)\)", guide.read_text(encoding="utf-8")):
            if not link.startswith(("https://", "http://")):
                assert (guide.parent / link.split("#", 1)[0]).is_file(), (guide, link)
    assert "scripts/init.py" in (project / "README.md").read_text(encoding="utf-8")
    assert "vendor/micro-service" not in (project / "pom.xml").read_text(encoding="utf-8")
    for name in ("init", "dev", "add_service", "build", "package", "migrate", "compose"):
        result = subprocess.run(
            [sys.executable, str(project / "scripts" / (name + ".py")), "--help"],
            cwd=project,
            capture_output=True,
        )
        assert result.returncode == 0, (name, result.stderr)
    assert ".vscode/launch.json" in git(project, "ls-files")


def test_generated_ci_rejects_missing_service_and_missing_test(project):
    path = project / ".github/workflows/checks.yml"
    workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
    shell = next(step["run"] for step in workflow["jobs"]["scope"]["steps"] if "run" in step)
    code = shell.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]

    def check():
        return subprocess.run(
            [sys.executable, "-c", code],
            cwd=project,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env={**os.environ, "PYTHONUTF8": "0", "PYTHONIOENCODING": "cp936"},
        )

    assert check().returncode == 0
    entries = workflow["jobs"]["python"]["strategy"]["matrix"]["include"]
    entry = entries.pop()
    path.write_text(yaml.safe_dump(workflow, allow_unicode=True), encoding="utf-8")
    result = check()
    assert result.returncode != 0 and "CI模块" in result.stderr
    entry["tests"] = "services/inspection-service/tests/missing.py"
    entries.append(entry)
    path.write_text(yaml.safe_dump(workflow, allow_unicode=True), encoding="utf-8")
    result = check()
    assert result.returncode != 0 and "测试不存在" in result.stderr


def test_create_exports_explicit_commit_not_dirty_generator(project, tmp_path, monkeypatch):
    import create as creator

    source = tmp_path / "scaffold-source"
    source.mkdir()
    for name in (
        "sdk",
        "services",
        "scripts",
        "config",
        "infra",
        "deploy",
        "web",
        "docs/standards",
        "docs/guides/05-工程检查与监控指南.md",
        "docs/guides/06-SDK使用参考.md",
        "docs/guides/07-系统安全验收清单.md",
        ".github",
        "tests/fixtures",
        "tests/acceptance/smoke.cjs",
        ".mvn",
        "pom.xml",
        "mvnw",
        "mvnw.cmd",
        "pyproject.toml",
        "uv.lock",
        ".editorconfig",
        ".gitattributes",
    ):
        generation.copy_source(generation.FRAMEWORK / name, source / name)
    git(source, "init", "-b", "main")
    ref = commit(source)
    write(source / "scripts/generation.py", "raise RuntimeError('dirty-source-must-not-run')\n")
    monkeypatch.setattr(creator, "__file__", str(source / "scripts/create.py"))
    output = tmp_path / "真正导出 project"
    creator.create(
        argparse.Namespace(
            output=output,
            ref=ref,
            project="new-platform",
            java_service="device-service",
            java_package="com.acme.device",
            python_service="recognition-service",
            python_package="recognition",
            port_offset=300,
        )
    )
    assert ref in (output / "README.md").read_text(encoding="utf-8")
    assert set(services(output)) == {
        "identity-service",
        "api-gateway",
        "device-service",
        "recognition-service",
    }
    assert not (output / "template").exists()
    assert not (output / "services/sample-java-service").exists()


def test_add_submodule_clone_then_add_fourth_service_without_local_state(project, tmp_path):
    override = project / "deploy/compose.override.yaml"
    write(override, "services:\n  equipment-service:\n    mem_limit: 768m\n")
    tasks = project / ".vscode/tasks.json"
    data = json.loads(tasks.read_text(encoding="utf-8"))
    data["tasks"].append(
        {"label": "personal", "type": "process", "command": "echo", "args": ["keep"]}
    )
    write(tasks, json.dumps(data))
    for name, target in (("report-service", project), ("audit-service", tmp_path / "clone-b")):
        if target != project:
            commit(project)
            git(tmp_path, "clone", "--recurse-submodules", str(project), str(target))
            assert not (target / ".local").exists()
        remote = tmp_path / (name + "-remote")
        create_service(
            argparse.Namespace(
                language="java",
                name=name,
                package="com.acme." + name.split("-")[0],
                project_root=target,
                output=remote,
            )
        )
        git(remote, "init", "-b", "main")
        ref = commit(remote)
        add_service(
            target,
            argparse.Namespace(
                repo=str(remote),
                ref=ref,
                language="java",
                name=name,
                package="com.acme." + name.split("-")[0],
                port=49000 if name.startswith("report") else 49001,
            ),
        )
        assert git(target / "services" / name, "rev-parse", "HEAD") == ref
        assert git(target, "ls-files", "--stage", "services/" + name).startswith("160000 " + ref)
        assert services(target)[name]["source"] == "submodule"
        assert "service:" + name in {
            v["name"]
            for v in json.loads((target / ".vscode/launch.json").read_text(encoding="utf-8"))[
                "configurations"
            ]
        }
        assert any(
            v["label"] == "personal"
            for v in json.loads((target / ".vscode/tasks.json").read_text(encoding="utf-8"))[
                "tasks"
            ]
        )
        assert (target / "deploy/compose.override.yaml").read_bytes() == override.read_bytes()
        assert not (target / ".local/runtime").exists()


def test_ide_jsonc_conflict_keeps_original_and_produces_candidate(project):
    path = project / ".vscode/tasks.json"
    write(path, '{ // personal comment\n"version":"2.0.0", "tasks": []\n}\n')
    before = path.read_bytes()
    with pytest.raises(ValueError, match="候选"):
        install(project, project_config(project)["services"])
    assert path.read_bytes() == before
    assert (project / ".local/ide-candidates/tasks.json").is_file()


def test_selected_service_does_not_publish_other_service_configuration(project):
    selected = project_config(project, "inspection-service")
    names = {path.name for path in nacos_files(project, selected["selected"])}
    assert "application-shared.yaml" in names
    assert "identity-service.yaml" in names
    assert "inspection-service.yaml" in names
    assert not any(name.startswith("equipment-service") for name in names)


def test_new_python_submodule_has_portable_frozen_lock(project, tmp_path, monkeypatch):
    import os
    import shutil

    uv = shutil.which("uv")
    assert uv, "测试要求根 README 声明的 uv"
    monkeypatch.setenv("UV_OFFLINE", "1")
    remote = tmp_path / "python-remote"
    create_service(
        argparse.Namespace(
            language="python",
            name="scoring-service",
            package="scoring",
            project_root=project,
            output=remote,
        )
    )
    git(remote, "init", "-b", "main")
    ref = commit(remote)
    add_service(
        project,
        argparse.Namespace(
            repo=str(remote),
            ref=ref,
            language="python",
            name="scoring-service",
            package="scoring",
            port=49005,
        ),
    )
    folder = project / "services/scoring-service"
    subprocess.run(
        [uv, "lock", "--check", "--project", str(folder)],
        check=True,
        env={k: v for k, v in os.environ.items() if k != "UV_PROJECT_ENVIRONMENT"},
    )
    lock = (folder / "uv.lock").read_text(encoding="utf-8")
    assert "../../sdk/python" in lock
    assert str(tmp_path) not in lock
    assert git(folder, "status", "--porcelain") == ""
    assert not (project / ".local/runtime").exists()


def test_missing_ref_has_no_host_mutations(project, tmp_path):
    remote = tmp_path / "remote"
    create_service(
        argparse.Namespace(
            language="java",
            name="report-service",
            package="com.acme.report",
            project_root=project,
            output=remote,
        )
    )
    git(remote, "init", "-b", "main")
    commit(remote)
    before = (project / "config/services.yaml").read_bytes()
    with pytest.raises(subprocess.CalledProcessError):
        add_service(
            project,
            argparse.Namespace(
                repo=str(remote),
                ref="missing-ref",
                language="java",
                name="report-service",
                package="com.acme.report",
                port=49000,
            ),
        )
    assert (project / "config/services.yaml").read_bytes() == before
    assert not (project / ".gitmodules").exists()
    assert not (project / "services/report-service").exists()


@pytest.mark.parametrize("requested", ["equipment-service", "inspection-service", "all"])
def test_selected_gateway_routes_match_provisioned_audiences(project, requested):
    path = project / "config/nacos/api-gateway.yaml"
    before = path.read_bytes()
    config = project_config(project, requested)
    content = nacos_content(project, path, config["selected"])
    routes = yaml.safe_load(content)["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
    assert {route["metadata"]["platform.audience"] for route in routes} == set(config["services"])
    assert path.read_bytes() == before
    if requested == "all":
        assert content == path.read_text(encoding="utf-8")


def test_selected_gateway_keeps_already_initialized_service(project):
    path = project / "config/nacos/api-gateway.yaml"
    write(project / ".local/runtime/api-gateway-secrets/readers.json", json.dumps({"readers": [
        {"audience": "inspection-service", "client_id": "previous-reader", "secret_file": "/run/secrets/previous.password"}
    ]}))
    assert nacos_content(project, path, project_config(project, "equipment-service")["selected"]) == path.read_text(encoding="utf-8")


def test_selected_gateway_preserves_custom_routes_and_shared_data_ids(project, monkeypatch):
    path = project / "config/nacos/api-gateway.yaml"
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    routes = document["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
    routes.append({"id": "custom", "uri": "lb://custom-shared", "metadata": {"platform.audience": "custom-shared"}})
    write(path, yaml.safe_dump(document, sort_keys=False))
    write(project / "config/nacos/project-shared.yaml", "custom_flag: true\n")
    before = {p.name: p.read_bytes() for p in (project / "config/nacos").iterdir() if p.is_file()}
    captured = {}
    tool = load_tool("scripts/init.py")
    def publish(root, entry, *args):
        assert root == project and entry == "scripts/config/publish.py"
        view = Path(args[args.index("--source") + 1])
        captured["path"] = view
        selected = [args[i + 1] for i, value in enumerate(args) if value == "--data-id"]
        assert "equipment-service.yaml" in selected and "inspection-service.yaml" not in selected
        assert "project-shared.yaml" in selected
        assert (view / "inspection-service.yaml").is_file()
        rendered = yaml.safe_load((view / "api-gateway.yaml").read_text(encoding="utf-8"))
        actual = rendered["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
        assert {row["metadata"]["platform.audience"] for row in actual} == {"equipment-service", "custom-shared"}
        assert (view / "project-shared.yaml").read_bytes() == before["project-shared.yaml"]
    monkeypatch.setattr(tool, "tool", publish)
    tool.publish_nacos(project, project_config(project, "equipment-service"), project / ".local/environment.env")
    assert not captured["path"].exists()
    assert before == {p.name: p.read_bytes() for p in (project / "config/nacos").iterdir() if p.is_file()}


def test_selected_gateway_temporary_view_is_cleaned_after_publish_failure(project, monkeypatch):
    tool = load_tool("scripts/init.py")
    captured = {}
    def fail(root, entry, *args):
        captured["path"] = Path(args[args.index("--source") + 1])
        raise RuntimeError("expected publication failure")
    monkeypatch.setattr(tool, "tool", fail)
    with pytest.raises(RuntimeError, match="expected publication failure"):
        tool.publish_nacos(project, project_config(project, "inspection-service"), project / ".local/environment.env")
    assert not captured["path"].exists()


@pytest.mark.parametrize("before,after", [
    ({}, {"server/project/local/ns/api-gateway.yaml": "new"}),
    ({"server/project/local/ns/api-gateway.yaml": "same"}, {"server/project/local/ns/api-gateway.yaml": "same"}),
    ({"server/project/local/ns/api-gateway.yaml": "old"}, {"server/project/local/ns/api-gateway.yaml": "new"}),
    ({}, {"server/project/local/ns/equipment-service.yaml": "new"}),
])
def test_selected_gateway_returns_publication_snapshot_not_a_transient_changed_flag(project, monkeypatch, before, after):
    tool = load_tool("scripts/init.py")
    state = project / ".local/publish-state.json"
    write(state, json.dumps(before))
    def publish(root, entry, *args):
        write(state, json.dumps(after))
    monkeypatch.setattr(tool, "tool", publish)
    assert tool.publish_nacos(project, project_config(project, "equipment-service"), project / ".local/environment.env") == {
        key: value for key, value in after.items() if key.endswith("/api-gateway.yaml")
    }


def test_generated_ci_includes_security_and_monitoring_regressions(project):
    for root in (project, Path(__file__).resolve().parents[2]):
        workflow = yaml.safe_load((root / ".github/workflows/checks.yml").read_text(encoding="utf-8"))
        java = {entry["module"]: set(entry["tests"].split(",")) for entry in workflow["jobs"]["java"]["strategy"]["matrix"]["include"]}
        assert {"BrowserContentSecurityPolicyTest", "MonitoringMetricsBindingTest"} <= java["services/identity-service"]
        assert "GatewayTokenPreflightFilterTest" in java["services/api-gateway"]
        python = {entry["module"]: set(entry["tests"].split()) for entry in workflow["jobs"]["python"]["strategy"]["matrix"]["include"]}
        assert "sdk/python/tests/test_access_logging.py" in python["sdk/python"]
