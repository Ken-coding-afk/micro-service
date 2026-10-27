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
