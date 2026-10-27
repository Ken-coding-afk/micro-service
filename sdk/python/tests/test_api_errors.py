import json
from pathlib import Path
from typing import Annotated

import pytest
from fastapi import Body, FastAPI
from fastapi.testclient import TestClient

from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.web import PlatformMiddleware, install_error_handlers

CASES = json.loads(
    (Path(__file__).resolve().parents[3] / "tests/fixtures/api-errors-v1.json").read_text()
)["cases"]


def application():
    app = FastAPI()
    app.add_middleware(PlatformMiddleware)
    install_error_handlers(app)

    @app.get("/api/validation")
    async def validation(value: int):
        return {"value": value}

    @app.post("/api/body")
    async def body(value: Annotated[dict, Body()]):
        return value

    @app.get("/api/failure")
    async def failure():
        raise RuntimeError("password=private")

    @app.get("/api/busy")
    async def busy():
        raise PlatformError("SERVICE_BUSY", "忙碌", 429, headers={"Retry-After": "1"})

    @app.get("/api/timeout")
    async def timeout():
        raise PlatformError("UPSTREAM_TIMEOUT", "超时", 504)

    return app


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
def test_shared_inbound_error_contract(case):
    with TestClient(application(), raise_server_exceptions=False) as client:
        response = client.request(
            case["method"],
            case["path"],
            content=case.get("body"),
            headers={"X-Request-Id": "fixture-request", "Content-Type": "application/json"},
        )
    assert response.status_code == case["status"]
    body = response.json()
    assert set(body) == {"code", "message", "data", "request_id"}
    assert body["code"] == case["code"] and body["data"] is None
    assert body["request_id"] == response.headers["x-request-id"] == "fixture-request"
    assert response.headers["cache-control"] == "no-store"
    assert "private" not in response.text
    if case["name"] == "method":
        assert "GET" in response.headers["allow"]
    if case["name"] == "busy":
        assert response.headers["retry-after"] == "1"


def test_non_business_http_errors_remain_native():
    with TestClient(application()) as client:
        response = client.get("/not-an-api")
    assert response.json() == {"detail": "Not Found"}


async def test_started_response_never_rewritten_as_second_json():
    async def app(scope, receive, send):
        await send({"type": "http.response.start", "status": 200, "headers": []})
        raise RuntimeError("stream failed")

    messages = []

    async def send(value):
        messages.append(value)

    async def receive():
        return {"type": "http.disconnect"}

    with pytest.raises(RuntimeError):
        await PlatformMiddleware(app)(
            {"type": "http", "path": "/api/stream", "headers": []}, receive, send
        )
    assert len(messages) == 1 and messages[0]["status"] == 200
