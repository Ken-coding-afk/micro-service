"""资源声明、组件模式和本地初始化不变量。"""

import argparse
import importlib.util
import sys
from pathlib import Path
from unittest.mock import Mock

import httpx
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


init = load_module("resource_init_contract", "tools/infra/init.py")
up = load_module("infra_up_contract", "tools/infra/up.py")


def declaration(rid="namespace", dependencies=None):
    return {
        "resource_id": rid,
        "component": "nacos",
        "owner_service": "platform",
        "management": "verify",
        "name_binding": {"namespace": "default"},
        "depends_on": dependencies or [],
    }


def test_resource_dependencies_sorted(tmp_path):
    path = tmp_path / "resources.yaml"
    path.write_text(
        yaml.safe_dump(
            {"resources": [declaration("second", ["first"]), declaration("first")]}
        ),
        encoding="utf-8",
    )
    assert [item["resource_id"] for item in init.load_resources(path)] == [
        "first",
        "second",
    ]


@pytest.mark.parametrize(
    "resources",
    [
        [declaration(), declaration()],
        [declaration(dependencies=["missing"])],
        [declaration("a", ["b"]), declaration("b", ["a"])],
        [declaration() | {"sql": "DROP DATABASE ignored"}],
        [declaration() | {"management": "reset"}],
        [declaration() | {"name_binding": {"database": "wrong"}}],
    ],
)
def test_invalid_resource_declaration_rejected(tmp_path, resources):
    path = tmp_path / "resources.yaml"
    path.write_text(yaml.safe_dump({"resources": resources}), encoding="utf-8")
    with pytest.raises(ValueError):
        init.load_resources(path)


def test_external_components_never_selected_for_installation():
    values = {
        "APP_ENV": "test",
        "RABBIT_HOST": "mq.internal",
        "PG_MODE": "external",
        "REDIS_MODE": "self_hosted",
        "NACOS_MODE": "external",
        "RABBIT_MODE": "external",
    }
    assert up.selected_services(values) == ["redis"]


def test_prepare_existing_example_fills_placeholders_without_rotating(
    tmp_path, monkeypatch
):
    path = tmp_path / "environment.env"
    example = ROOT / "template/config/environments/local.env.example"
    path.write_text(example.read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(
        init.subprocess, "run", Mock(return_value=argparse.Namespace(returncode=0))
    )
    args = argparse.Namespace(env_file=str(path))
    assert init.phase_prepare_local(args) == 0
    first = init.contract.parse_env_file(path).values
    assert init.phase_prepare_local(args) == 0
    assert init.contract.parse_env_file(path).values == first
    assert not any(value.startswith("<") for value in first.values())


def test_prepare_rejects_non_local_before_writing(tmp_path):
    path = tmp_path / "environment.env"
    before = "APP_ENV=prod\nPROJECT_CODE=sample\n"
    path.write_text(before, encoding="utf-8")
    with pytest.raises(ValueError, match="local"):
        init.phase_prepare_local(argparse.Namespace(env_file=str(path)))
    assert path.read_text(encoding="utf-8") == before


def test_local_lock_rejects_concurrent_initializer(tmp_path):
    with (
        init.LocalLock(tmp_path / "init.lock"),
        pytest.raises(ValueError, match="并发"),
        init.LocalLock(tmp_path / "init.lock"),
    ):
        pytest.fail("不能获得同一环境的第二把锁")


def test_rabbit_verify_only_reads_and_checks_acl(tmp_path, monkeypatch):
    requests = []

    def handle(request):
        requests.append((request.method, str(request.url)))
        return httpx.Response(200, json={})

    real_client = httpx.Client
    monkeypatch.setattr(
        init.httpx,
        "Client",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs),
    )
    resource = {"roles": {"app": "app"}, "management": "verify"}
    env = {
        "APP_ENV": "local",
        "PROJECT_CODE": "sample",
        "RABBIT_HOST": "mq.internal",
        "RABBIT_ADMIN_USER": "admin",
        "RABBIT_ADMIN_PASSWORD": "dummy",
        "_ENV_DIR": str(tmp_path),
    }
    assert init._ensure_rabbit_vhost(resource, env, {}) == "VERIFIED"
    assert all(
        method == "GET" and url.startswith("http://mq.internal:")
        for method, url in requests
    )


def test_rabbit_permission_failure_not_reported_as_success(tmp_path, monkeypatch):
    def handle(request):
        if request.method == "PUT" and "/permissions/" in str(request.url):
            return httpx.Response(403, json={})
        return httpx.Response(200, json={})

    real_client = httpx.Client
    monkeypatch.setattr(
        init.httpx,
        "Client",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs),
    )
    resource = {"roles": {"app": "app"}, "management": "create"}
    env = {
        "APP_ENV": "local",
        "PROJECT_CODE": "sample",
        "RABBIT_HOST": "mq.internal",
        "RABBIT_ADMIN_USER": "admin",
        "RABBIT_ADMIN_PASSWORD": "dummy",
        "_ENV_DIR": str(tmp_path),
    }
    assert init._ensure_rabbit_vhost(resource, env, {}) == "FAILED"


def test_project_redis_template_used_once_and_runtime_preserved(tmp_path, monkeypatch):
    path = tmp_path / "environment.env"
    path.write_text(
        (ROOT / "template/config/environments/local.env.example").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    project_template = tmp_path / "infra/redis/redis.conf"
    project_template.parent.mkdir(parents=True)
    project_template.write_text(
        "requirepass __REDIS_PASSWORD__\nmaxmemory 123mb\n", encoding="utf-8"
    )
    monkeypatch.setattr(init.subprocess, "run", Mock(return_value=argparse.Namespace(returncode=0)))
    args = argparse.Namespace(env_file=str(path), redis_template=project_template)
    assert init.phase_prepare_local(args) == 0
    first_env = path.read_bytes()
    runtime = tmp_path / "redis/redis.conf"
    expected = (
        "requirepass "
        + init.contract.parse_env_file(path).values["REDIS_PASSWORD"]
        + "\nmaxmemory 123mb\n"
    )
    assert runtime.read_text(encoding="utf-8") == expected
    runtime.write_text(expected + "# operator-owned\n", encoding="utf-8")
    project_template.write_text("new-template\n", encoding="utf-8")
    assert init.phase_prepare_local(args) == 0
    assert runtime.read_text(encoding="utf-8") == expected + "# operator-owned\n"
    assert path.read_bytes() == first_env


def test_missing_explicit_redis_template_never_falls_back(tmp_path, monkeypatch):
    path = tmp_path / "environment.env"
    path.write_text(
        (ROOT / "template/config/environments/local.env.example").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    before = path.read_bytes()
    monkeypatch.setattr(init.subprocess, "run", lambda *a, **k: pytest.fail("no component calls"))
    with pytest.raises(FileNotFoundError):
        init.phase_prepare_local(
            argparse.Namespace(env_file=str(path), redis_template=tmp_path / "missing.conf")
        )
    assert path.read_bytes() == before
    assert not (tmp_path / "redis/redis.conf").exists()
