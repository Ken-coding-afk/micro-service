"""真实 Java 应用启动/关闭 + Python 原生发现观察；不验证业务鉴权。"""

import asyncio
import os
import shutil
import socket
from pathlib import Path

import httpx
import pytest

from ken_platform_sdk.config import PlatformSettings
from ken_platform_sdk.nacos import NacosProvider


@pytest.mark.skipif(
    not os.getenv("PLATFORM_TEST_JAVA_HOME") or not os.getenv("PLATFORM_TEST_ENV_FILE"),
    reason="未提供本地 Java/Nacos 集成测试绑定",
)
async def test_real_java_ready_registration_and_graceful_shutdown(tmp_path):
    source = Path(os.environ["PLATFORM_TEST_ENV_FILE"]).resolve()
    root = source.parent.parent
    values = dict(
        line.split("=", 1)
        for line in source.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    assert values["APP_ENV"] == "local"
    secret = tmp_path / "runtime.password"
    secret.write_text(values["NACOS_RUNTIME_PASSWORD"], encoding="utf-8")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    settings = PlatformSettings(
        project_code=values["PROJECT_CODE"],
        app_env="local",
        service_name="api-gateway",
        service_bind_port=port,
        service_advertise_host="192.0.2.57",
        service_advertise_port=port,
        nacos_server_addr=values["NACOS_SERVER_ADDR"],
        nacos_namespace=values["NACOS_NAMESPACE"],
        nacos_runtime_user=values["NACOS_RUNTIME_USER"],
        nacos_runtime_password_secret_file=secret,
        nacos_log_dir=tmp_path / "observer-logs",
    )
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("NACOS_", "SERVICE_"))
    }
    for field, value in settings.model_dump().items():
        if field.startswith(("nacos_", "service_")) and field != "nacos_log_dir":
            environment[field.upper()] = str(value)
    environment.update(PROJECT_CODE=settings.project_code, APP_ENV="local")
    jar = root / "services/api-gateway/target/api-gateway.jar"
    classes = root / "services/api-gateway/target/test-classes"
    probe = classes / "com/ken/platform/gateway/GatewayLifecycleProbe.class"
    assert jar.is_file() and probe.is_file(), "先构建 api-gateway 与集成探针"
    # 完整 test-classes 会被组件扫描发现局部测试配置，仅加入启停探针。
    probe_classes = tmp_path / "probe-classes"
    target = probe_classes / "com/ken/platform/gateway/GatewayLifecycleProbe.class"
    target.parent.mkdir(parents=True)
    shutil.copy2(probe, target)
    java = Path(os.environ["PLATFORM_TEST_JAVA_HOME"]) / "bin/java.exe"
    observer = NacosProvider(settings)
    await observer.open()
    with (tmp_path / "java.log").open("w", encoding="utf-8") as log:
        process = await asyncio.create_subprocess_exec(
            str(java),
            "-Dfile.encoding=UTF-8",
            f"-Dloader.path={probe_classes}",
            "-Dloader.main=com.ken.platform.gateway.GatewayLifecycleProbe",
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
            async with httpx.AsyncClient() as http, asyncio.timeout(40):
                while True:
                    assert process.returncode is None, "Java 启动失败，检查隔离目录中的 java.log"
                    try:
                        response = await http.get(
                            f"http://127.0.0.1:{port}/actuator/health", timeout=1
                        )
                        hosts = await observer.query("api-gateway")
                        if response.status_code == 200 and any(host.port == port for host in hosts):
                            assert response.json()["status"] == "UP"
                            break
                    except httpx.HTTPError:
                        pass
                    await asyncio.sleep(0.2)
            assert process.stdin is not None
            process.stdin.write(b"\n")
            await process.stdin.drain()
            await asyncio.wait_for(process.wait(), 15)
            assert process.returncode == 0
            async with asyncio.timeout(15):
                while any(host.port == port for host in await observer.query("api-gateway")):
                    await asyncio.sleep(0.2)
        finally:
            if process.returncode is None:
                process.kill()
                await asyncio.wait_for(process.wait(), 10)
            if process.stdin is not None:
                process.stdin.close()
            await observer.close()
