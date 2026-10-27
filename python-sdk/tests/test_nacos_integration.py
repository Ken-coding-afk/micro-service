"""显式传 PLATFORM_TEST_ENV_FILE 才连接本机 Nacos；不改配置正文，仅注册唯一探针。"""

import asyncio
import os
import uuid
from pathlib import Path

import pytest

from ken_platform_sdk.config import PlatformSettings, load_runtime
from ken_platform_sdk.nacos import NacosProvider


@pytest.mark.skipif(not os.getenv("PLATFORM_TEST_ENV_FILE"), reason="未提供本地组件测试绑定")
async def test_native_nacos_reads_scoped_config_registers_and_deregisters(tmp_path):
    source = Path(os.environ["PLATFORM_TEST_ENV_FILE"])
    values = dict(
        line.split("=", 1)
        for line in source.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    assert values["APP_ENV"] == "local", "此探针只允许本机 local 环境"
    secret = tmp_path / "runtime.password"
    if values.get("NACOS_RUNTIME_PASSWORD_SECRET_FILE"):
        password_path = Path(values["NACOS_RUNTIME_PASSWORD_SECRET_FILE"])
        password = password_path.read_text(encoding="utf-8")
    else:
        password = values["NACOS_RUNTIME_PASSWORD"]
    secret.write_text(password, encoding="utf-8")
    settings = PlatformSettings(
        project_code=values["PROJECT_CODE"],
        app_env="local",
        service_name="t05a-probe-" + uuid.uuid4().hex[:10],
        service_bind_port=18555,
        service_advertise_host="192.0.2.56",
        service_advertise_port=18555,
        nacos_server_addr=values["NACOS_SERVER_ADDR"],
        nacos_namespace=values["NACOS_NAMESPACE"],
        nacos_runtime_user=values["NACOS_RUNTIME_USER"],
        nacos_runtime_password_secret_file=secret,
        nacos_log_dir=tmp_path / "nacos-logs",
    )
    provider = NacosProvider(settings)
    try:
        await provider.open()
        config = load_runtime(
            await provider.get_config("application-shared.yaml"),
            await provider.get_config("api-gateway.yaml"),
        )
        assert config.http.get_retry_extra == 1
        await provider.register()
        async with asyncio.timeout(15):
            while not await provider.query(settings.service_name):
                await asyncio.sleep(0.2)
        hosts = await provider.query(settings.service_name)
        assert [(host.host, host.port) for host in hosts] == [("192.0.2.56", 18555)]
        await provider.deregister()
        async with asyncio.timeout(15):
            while await provider.query(settings.service_name):
                await asyncio.sleep(0.2)
    finally:
        try:
            await provider.deregister()
        finally:
            await provider.close()
    # 注册地址仅用于发现合同，不代表跨主机 HTTP 路由已通过。
