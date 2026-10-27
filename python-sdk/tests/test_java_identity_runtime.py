"""身份服务完整装配：真实打包依赖、PG/Redis/Nacos、HTTP 与正常摘除。"""

import asyncio
import html
import json
import os
import re
import shutil
import socket
import uuid
from pathlib import Path

import httpx
import pytest

from ken_platform_sdk.config import PlatformSettings
from ken_platform_sdk.nacos import NacosProvider

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.skipif(
    not os.getenv("PLATFORM_TEST_JAVA_HOME")
    or not os.getenv("PLATFORM_TEST_IDENTITY_RUNTIME_BINDING"),
    reason="未提供隔离身份服务完整运行绑定",
)
async def test_packaged_identity_http_registration_and_graceful_shutdown(tmp_path):
    binding = json.loads(
        Path(os.environ["PLATFORM_TEST_IDENTITY_RUNTIME_BINDING"]).read_text(encoding="utf-8")
    )
    assert binding["test_only"] is True
    database = json.loads(Path(binding["database_binding"]).read_text(encoding="utf-8"))
    assert database["test_only"] is True
    values = dict(
        line.split("=", 1)
        for line in Path(binding["environment_file"]).read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    assert values["APP_ENV"] == "local"
    assert values["PROJECT_CODE"].startswith("t02t")
    assert values["DB_NAME"] == database["dbname"]
    assert values["DB_USER"] == database["username"]
    assert not any("ADMIN" in key or "PUBLISH" in key for key in values)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    origin = f"http://localhost:{port}"
    values.update(
        SERVICE_BIND_PORT=str(port),
        SERVICE_ADVERTISE_PORT=str(port),
        PUBLIC_ORIGIN=origin,
        IDENTITY_INTERNAL_ORIGIN=f"http://{values['SERVICE_ADVERTISE_HOST']}:{port}",
    )
    settings = PlatformSettings(
        project_code=values["PROJECT_CODE"],
        app_env="local",
        service_name="identity-service",
        service_bind_port=port,
        service_advertise_host=values["SERVICE_ADVERTISE_HOST"],
        service_advertise_port=port,
        nacos_server_addr=values["NACOS_SERVER_ADDR"],
        nacos_namespace=values["NACOS_NAMESPACE"],
        nacos_runtime_user=values["NACOS_RUNTIME_USER"],
        nacos_runtime_password_secret_file=values["NACOS_RUNTIME_PASSWORD_SECRET_FILE"],
        nacos_log_dir=tmp_path / "observer-logs",
    )
    allowed = {
        "PATH",
        "SYSTEMROOT",
        "COMSPEC",
        "WINDIR",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "APPDATA",
        "LOCALAPPDATA",
        "HOMEDRIVE",
        "HOMEPATH",
        "JAVA_HOME",
        "LANG",
        "TZ",
    }
    environment = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    environment.update(values)
    jar = ROOT / "services/identity-service/target/identity-service.jar"
    classes = ROOT / "services/identity-service/target/test-classes"
    assert (
        jar.is_file()
        and (classes / "com/ken/platform/identity/IdentityLifecycleProbe.class").is_file()
    )
    # 只加入启停探针；整个 test-classes 会让生产组件扫描发现局部测试配置。
    probe_classes = tmp_path / "probe-classes"
    target = probe_classes / "com/ken/platform/identity/IdentityLifecycleProbe.class"
    target.parent.mkdir(parents=True)
    shutil.copy2(classes / "com/ken/platform/identity/IdentityLifecycleProbe.class", target)
    java = Path(os.environ["PLATFORM_TEST_JAVA_HOME"]) / "bin/java.exe"
    observer = NacosProvider(settings)
    await observer.open()
    with (tmp_path / "java.log").open("w", encoding="utf-8") as log:
        process = await asyncio.create_subprocess_exec(
            str(java),
            "-Dfile.encoding=UTF-8",
            f"-Dloader.path={probe_classes}",
            "-Dloader.main=com.ken.platform.identity.IdentityLifecycleProbe",
            "-cp",
            str(jar),
            "org.springframework.boot.loader.launch.PropertiesLauncher",
            env=environment,
            cwd=tmp_path,
            stdout=log,
            stderr=asyncio.subprocess.STDOUT,
            stdin=asyncio.subprocess.PIPE,
        )
        try:
            async with httpx.AsyncClient(base_url=origin, trust_env=False, timeout=5) as http:
                async with asyncio.timeout(45):
                    while True:
                        assert process.returncode is None, "身份服务启动失败，检查隔离 java.log"
                        try:
                            response = await http.get("/actuator/health/readiness")
                            hosts = await observer.query("identity-service")
                            if response.status_code == 200 and any(x.port == port for x in hosts):
                                assert response.json() == {"status": "UP"}
                                assert any(
                                    x.host == settings.service_advertise_host and x.port == port
                                    for x in hosts
                                )
                                break
                        except httpx.HTTPError:
                            pass
                        await asyncio.sleep(0.2)
                for path in ("/actuator/health/liveness", "/actuator/health/readiness"):
                    response = await http.get(path)
                    assert response.status_code == 200
                    assert response.json() == {"status": "UP"}
                page = await http.get("/identity/admin/")
                assert page.status_code == 200
                assert '<div id="app"></div>' in page.text
                assert "no-store" in page.headers["cache-control"]
                assert "script-src 'self'" in page.headers["content-security-policy"]
                assert "set-cookie" not in page.headers
                asset = re.search(r'src="([^"]+\.js)"', page.text)[1]
                assert asset.startswith("/identity/admin/assets/")
                assert (await http.get(asset)).status_code == 200
                for route in (
                    "account",
                    "platform/tenants",
                    "platform/admins",
                    "users",
                    "permissions",
                    "roles",
                    "roles/management-grants",
                    "audits",
                    "sessions",
                ):
                    assert (await http.get("/identity/admin/" + route)).status_code == 200
                for missing in ("/identity/admin/unknown-page", "/identity/admin/assets/absent.js"):
                    absent = await http.get(missing)
                    assert absent.status_code == 404
                    assert '<div id="app"></div>' not in absent.text
                public = await http.get("/api/v1/identity/admin-config")
                assert public.status_code == 200
                assert len(public.json()["data"]) == 5
                assert "set-cookie" not in public.headers
                response = await http.get(
                    "/.well-known/openid-configuration",
                    headers={"X-Forwarded-Host": "untrusted.example", "X-Forwarded-Proto": "https"},
                )
                assert response.status_code == 200
                assert response.json()["issuer"] == origin
                assert "registration_endpoint" not in response.json()
                response = await http.get("/oauth2/jwks")
                assert response.status_code == 200
                keys = response.json()["keys"]
                assert keys and all(
                    key["kty"] == "RSA" and not ({"d", "p", "q", "dp", "dq", "qi"} & set(key))
                    for key in keys
                )
                for path in ("/actuator/env", "/connect/register", "/unknown"):
                    assert (await http.get(path)).status_code == 403
                response = await http.get(
                    "/api/v1/identity/me",
                    headers={"X-User-Id": "forged", "X-Tenant-Id": str(uuid.uuid4())},
                )
                assert response.status_code == 401
                assert response.json()["code"] == "INVALID_CREDENTIALS"
                assert response.json()["request_id"] == response.headers["x-request-id"]
                assert "location" not in response.headers and "set-cookie" not in response.headers
                assert (
                    await http.get(
                        "/api/v1/identity/me", headers={"Authorization": "Bearer invalid-fixture"}
                    )
                ).status_code == 401
                http.cookies.clear()
                response = await http.get("/identity/login")
                assert response.status_code == 200
                assert "text/html" in response.headers["content-type"]
                assert response.headers["cache-control"] == "no-store"
                cookie = response.headers["set-cookie"]
                assert (
                    "IDENTITY_SSO=" in cookie and "HttpOnly" in cookie and "SameSite=Lax" in cookie
                )
                assert "set-cookie" not in (await http.get("/identity/admin/account")).headers
                assert (
                    await http.post("/identity/login", data={"username": "absent"})
                ).status_code == 403
                response = await http.get("/identity/login")
                csrf = html.unescape(re.search(r'name="_csrf" value="([^"]+)"', response.text)[1])
                response = await http.post(
                    "/identity/login",
                    data={
                        "_csrf": csrf,
                        "realm": "PLATFORM",
                        "username": "runtime-probe-" + uuid.uuid4().hex,
                        "password": "not-an-existing-account",
                    },
                )
                assert response.status_code == 401
                assert "账号或密码无效" in response.text
                assert "jdbc:postgresql" not in response.text
                response = await http.get("/identity/session")
                assert response.status_code == 302
                assert response.headers["location"] == origin + "/identity/login"
            assert process.stdin is not None
            process.stdin.write(b"\n")
            await process.stdin.drain()
            await asyncio.wait_for(process.wait(), 20)
            assert process.returncode == 0
            async with asyncio.timeout(15):
                while any(x.port == port for x in await observer.query("identity-service")):
                    await asyncio.sleep(0.2)
        finally:
            if process.returncode is None:
                process.kill()
                await asyncio.wait_for(process.wait(), 10)
            if process.stdin is not None:
                process.stdin.close()
            await observer.close()
