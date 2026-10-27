"""发布客户端不越过 OAuth，不重放冲突写入，不泄漏令牌。"""

import hashlib
import importlib.util
import json
from pathlib import Path

import httpx
import pytest

spec = importlib.util.spec_from_file_location(
    "sync_permissions", Path(__file__).parents[2] / "scripts/deploy/sync_permissions.py"
)
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


@pytest.mark.parametrize(
    "url,env",
    [
        ("http://example.com", "local"),
        ("http://localhost", "prod"),
        ("https://user:pass@example.com", "prod"),
        ("https://example.com/oauth2", "prod"),
    ],
)
def test_origin_rejects_credential_exposure(url, env):
    with pytest.raises(ValueError):
        publication.origin(url, env)


@pytest.mark.parametrize("status", [200, 401, 409, 503])
def test_oauth_then_version_then_one_write(tmp_path, status):
    manifest = tmp_path / "permissions.yaml"
    manifest.write_text("service: sample-service\npermissions: []\n", encoding="utf-8")
    seen = []

    def handle(request):
        seen.append(request)
        if len(seen) == 1:
            assert request.url.path == "/oauth2/token"
            assert request.headers["authorization"].startswith("Basic ")
            return httpx.Response(200, json={"access_token": "secret-token"})
        assert request.headers["authorization"] == "Bearer secret-token"
        if len(seen) == 2:
            return httpx.Response(200, json={"data": {"catalog_version": 4}})
        body = json.loads(request.content)
        assert body["catalog_version"] == 4
        assert body["content"].encode("utf-8") == manifest.read_bytes()
        assert body["checksum"] == hashlib.sha256(manifest.read_bytes()).hexdigest()
        return httpx.Response(status, json={"data": {}, "error": "secret-token"})

    with httpx.Client(
        transport=httpx.MockTransport(handle), follow_redirects=False
    ) as client:
        if status == 200:
            publication.publish(
                client,
                "https://identity.example",
                "publisher",
                "secret",
                manifest,
                "1.0.0",
            )
        else:
            with pytest.raises(ValueError) as error:
                publication.publish(
                    client,
                    "https://identity.example",
                    "publisher",
                    "secret",
                    manifest,
                    "1.0.0",
                )
            assert "secret-token" not in str(error.value)
    assert len(seen) == 3
