"""发布持久化故障回归；HTTP 为受控外部边界，文件读写使用真实临时目录。"""

import argparse
import hashlib
import json
from pathlib import Path

import httpx
import pytest
from test_config_validation import valid_values

from scripts.config import publish


@pytest.fixture
def publication(tmp_path, monkeypatch):
    env = tmp_path / "environment.env"
    values = valid_values() | {
        "NACOS_PUBLISH_USER": "publisher",
        "NACOS_PUBLISH_PASSWORD": "test-only",
    }
    env.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
    source = tmp_path / "source"
    source.mkdir()
    (source / "application-shared.yaml").write_text("log_level: INFO\n", encoding="utf-8")
    monkeypatch.setattr(publish, "_login", lambda *args: "test-token")
    return argparse.Namespace(env_file=str(env), source=str(source), dry_run=False)


def transport(monkeypatch, handler):
    client = httpx.Client
    monkeypatch.setattr(
        publish.httpx,
        "Client",
        lambda **kwargs: client(transport=httpx.MockTransport(handler), **kwargs),
    )


@pytest.mark.parametrize("unchanged", [False, True])
def test_publication_never_truncates_state_file(publication, monkeypatch, unchanged):
    state = Path(publication.env_file).parent / "publish-state.json"
    write = Path.write_text

    def guarded_write(path, *args, **kwargs):
        assert path != state, "不能直接截断已保存的发布状态"
        return write(path, *args, **kwargs)

    monkeypatch.setattr(Path, "write_text", guarded_write)
    writes = []

    def handle(request):
        if request.method == "POST":
            writes.append(request)
            return httpx.Response(200, json={"code": 0, "data": True})
        return (
            httpx.Response(200, json={"code": 0, "data": {"content": "log_level: INFO\n"}})
            if unchanged
            else httpx.Response(404)
        )

    transport(monkeypatch, handle)
    assert publish.publish(publication) == 0
    assert len(writes) == (0 if unchanged else 1)
    assert list(json.loads(state.read_text(encoding="utf-8")).values()) == [
        hashlib.sha256(b"log_level: INFO\n").hexdigest()
    ]
    assert not state.with_suffix(".json.tmp").exists()


def test_unchanged_checkpoint_survives_later_network_failure(publication, monkeypatch):
    (Path(publication.source) / "identity-service.yaml").write_text("{}\n", encoding="utf-8")

    def handle(request):
        if request.url.params["dataId"] == "identity-service.yaml":
            raise httpx.ConnectError("test disconnect")
        return httpx.Response(200, json={"code": 0, "data": {"content": "log_level: INFO\n"}})

    transport(monkeypatch, handle)
    assert publish.publish(publication) == 1
    state = Path(publication.env_file).parent / "publish-state.json"
    assert len(json.loads(state.read_text(encoding="utf-8"))) == 1


def test_replace_failure_preserves_existing_state(publication, monkeypatch):
    state = Path(publication.env_file).parent / "publish-state.json"
    state.write_text('{"other-config": "existing-digest"}', encoding="utf-8")
    transport(
        monkeypatch,
        lambda request: (
            httpx.Response(200, json={"code": 0, "data": True})
            if request.method == "POST"
            else httpx.Response(404)
        ),
    )
    replace = Path.replace

    def failed_replace(path, target):
        if target == state:
            raise OSError("test disk failure")
        return replace(path, target)

    monkeypatch.setattr(Path, "replace", failed_replace)
    assert publish.publish(publication) == 1
    assert json.loads(state.read_text(encoding="utf-8")) == {"other-config": "existing-digest"}


def test_dry_run_does_not_save_state(publication, monkeypatch):
    publication.dry_run = True
    transport(
        monkeypatch,
        lambda request: httpx.Response(
            200, json={"code": 0, "data": {"content": "log_level: INFO\n"}}
        ),
    )
    assert publish.publish(publication) == 0
    assert not (Path(publication.env_file).parent / "publish-state.json").exists()
