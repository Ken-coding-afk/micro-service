from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from ken_platform_sdk.observation import ProtectionMetrics
from ken_platform_sdk.web import PlatformMiddleware


def test_metrics_listener_lifecycle_uses_component_and_releases_resources(monkeypatch):
    from unittest.mock import Mock

    from ken_platform_sdk import observation

    server, thread = Mock(), Mock()
    start = Mock(return_value=(server, thread))
    monkeypatch.setattr(observation, "start_http_server", start)
    metrics = ProtectionMetrics()
    metrics.start("127.0.0.1", 0)
    start.assert_not_called()
    metrics.start("127.0.0.1", 9100)
    start.assert_called_once_with(9100, addr="127.0.0.1", registry=metrics.registry)
    metrics.stop()
    metrics.stop()
    server.shutdown.assert_called_once()
    server.server_close.assert_called_once()
    thread.join.assert_called_once()


def test_http_histogram_uses_route_pattern_not_concrete_ids():
    metrics = ProtectionMetrics()
    app = FastAPI()
    app.add_middleware(PlatformMiddleware, runtime=SimpleNamespace(metrics=metrics, inbound=None))

    @app.get("/api/v1/items/{item_id}")
    def item(item_id: str):
        return {"id": item_id}

    with TestClient(app) as client:
        assert client.get("/api/v1/items/secret-user-id").status_code == 200
        assert client.get("/api/v1/items/another-id").status_code == 200
    text = metrics.render().decode()
    assert 'route="/api/v1/items/{item_id}"' in text
    assert "secret-user-id" not in text and "another-id" not in text
    assert (
        'platform_http_request_duration_seconds_count{method="GET",route="/api/v1/items/{item_id}",status="200"} 2.0'
        in text
    )
