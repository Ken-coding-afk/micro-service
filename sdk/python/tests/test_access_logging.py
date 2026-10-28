import asyncio
import io
import json
import logging
import socket

import httpx
import pytest
import uvicorn

from ken_platform_sdk.log_context import (
    ContextJsonFormatter,
    SafeAccessLogFilter,
    configure_logging,
)


@pytest.mark.parametrize("structured", [False, True])
async def test_real_uvicorn_keeps_request_query_but_drops_it_from_every_handler(
    monkeypatch, structured
):
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    handler.setFormatter(ContextJsonFormatter() if structured else logging.Formatter("%(message)s"))
    access = logging.getLogger("uvicorn.access")
    monkeypatch.setattr(access, "handlers", [handler])
    monkeypatch.setattr(access, "filters", [])
    monkeypatch.setattr(access, "propagate", False)
    monkeypatch.setattr(access, "level", logging.INFO)
    monkeypatch.setattr(logging.getLogger(), "handlers", [logging.NullHandler()])
    monkeypatch.setattr(logging.getLogger("uvicorn"), "handlers", [])
    configure_logging("fixture")
    configure_logging("fixture")
    assert sum(isinstance(f, SafeAccessLogFilter) for f in access.filters) == 1

    async def app(scope, receive, send):
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": scope["query_string"]})

    server = uvicorn.Server(uvicorn.Config(app, log_config=None, lifespan="off", http="h11"))
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    task = asyncio.create_task(server.serve(sockets=[listener]))
    try:
        async with asyncio.timeout(5):
            while not server.started:
                assert not task.done()
                await asyncio.sleep(0.01)
        async with httpx.AsyncClient(trust_env=False) as client:
            response = await client.get(
                f"http://127.0.0.1:{port}/docs/oauth2-redirect",
                params={"code": "fixture-private-code", "state": "fixture-private-state"},
            )
        assert response.status_code == 200
        assert response.text == "code=fixture-private-code&state=fixture-private-state"
        text = output.getvalue()
        message = json.loads(text)["message"] if structured else text
        assert '"GET /docs/oauth2-redirect HTTP/1.1" 200' in message
        assert "?" not in message and "fixture-private-" not in text
        if structured:
            assert json.loads(text)["service"] == "fixture"
    finally:
        server.should_exit = True
        await asyncio.wait_for(task, 5)
        listener.close()


@pytest.mark.parametrize("target", ["/api/items", "/api/items?page=1&access_token=private"])
def test_native_access_shape_preserves_method_path_version_status(target):
    record = logging.LogRecord(
        "uvicorn.access",
        logging.INFO,
        __file__,
        1,
        '%s - "%s %s HTTP/%s" %d',
        ("client", "POST", target, "1.1", 403),
        None,
    )
    assert SafeAccessLogFilter().filter(record)
    assert record.getMessage() == 'client - "POST /api/items HTTP/1.1" 403'


def test_unrecognized_access_record_is_not_formatted_with_private_values():
    record = logging.LogRecord(
        "uvicorn.access",
        logging.INFO,
        __file__,
        1,
        "unexpected private-body?code=private",
        (),
        None,
    )
    assert not SafeAccessLogFilter().filter(record)
