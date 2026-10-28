"""真实组件执行必须显式启用；这些测试不会调用 Docker。"""

import importlib.util
from pathlib import Path
from types import SimpleNamespace

import pytest


def test_integration_guard_runs_before_environment_or_process(monkeypatch):
    spec = importlib.util.spec_from_file_location(
        "integration_fixtures", Path(__file__).parents[1] / "conftest.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module.subprocess, "run", lambda *a, **k: pytest.fail("unexpected process"))
    request = SimpleNamespace(config=SimpleNamespace(getoption=lambda key: False))
    with pytest.raises(pytest.skip.Exception, match="真实集成未启用"):
        module.local_env.__wrapped__(request)


def test_explicit_integration_initialization_failure_is_not_skipped(monkeypatch):
    spec = importlib.util.spec_from_file_location(
        "integration_fixtures", Path(__file__).parents[1] / "conftest.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(
        module.subprocess,
        "run",
        lambda *a, **k: SimpleNamespace(returncode=2, stderr="unavailable"),
    )
    request = SimpleNamespace(config=SimpleNamespace(getoption=lambda key: True))
    with pytest.raises(AssertionError, match="unavailable"):
        module.local_env.__wrapped__(request)


def test_migration_import_and_session_fixture_require_explicit_integration(monkeypatch):
    monkeypatch.setattr(
        "subprocess.run", lambda *a, **k: pytest.fail("unexpected Docker process")
    )
    spec = importlib.util.spec_from_file_location(
        "migration_integration_guard", Path(__file__).parents[1] / "deploy/test_migration_basic.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    request = SimpleNamespace(config=SimpleNamespace(getoption=lambda key: False))
    with pytest.raises(pytest.skip.Exception, match="真实集成未启用"):
        module.pg_env.__wrapped__(request, None).__next__()
    with pytest.raises(pytest.skip.Exception, match="真实集成未启用"):
        module.alembic_image.__wrapped__(request)


def test_explicit_migration_environment_failure_is_not_skipped(monkeypatch):
    spec = importlib.util.spec_from_file_location(
        "migration_integration_unavailable", Path(__file__).parents[1] / "deploy/test_migration_basic.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module, "_docker_available", lambda: (False, "daemon unavailable"))
    request = SimpleNamespace(config=SimpleNamespace(getoption=lambda key: True))
    with pytest.raises(AssertionError, match="daemon unavailable"):
        module.pg_env.__wrapped__(request, None).__next__()


def test_kept_migration_resources_are_stopped_without_deleting_evidence(monkeypatch):
    spec = importlib.util.spec_from_file_location(
        "migration_resource_retention", Path(__file__).parents[1] / "deploy/test_migration_basic.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    commands = []
    monkeypatch.setattr(module, "docker", lambda args: commands.append(args))
    module.cleanup_resources(True)
    assert commands == [["stop", module.CONTAINER, module.CONTAINER2]]
