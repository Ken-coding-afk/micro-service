"""仓库共享集成测试夹具。

集成测试需要真实组件(设计/计划要求:配置漂移与资源初始化不能只用 mock):
conftest 负责按需 bootstrap——生成 .local/environment.env(prepare-local)、
启动 infra compose(postgres/redis/nacos,不含 mq profile)、等待健康。
默认不启动真实组件；--run-integration 显式选择后，环境不可用必须失败。
"""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

import pytest

FRAMEWORK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(FRAMEWORK_ROOT))
sys.path.insert(0, str(FRAMEWORK_ROOT / "scripts/config"))
import contract  # noqa: E402


def _compose_running() -> bool:
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            ".local/environment.env",
            "-f",
            "infra/compose.yaml",
            "ps",
            "--format",
            "json",
        ],
        cwd=FRAMEWORK_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    return result.returncode == 0 and "postgres" in (result.stdout or "")


def _wait_port(host: str, port: int, timeout: float = 120) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            time.sleep(2)
    return False


def pytest_addoption(parser):
    parser.addoption(
        "--run-integration",
        action="store_true",
        default=False,
        help="显式允许本目录测试初始化真实组件；默认不执行 Docker",
    )


@pytest.fixture(autouse=True)
def no_implicit_docker(request, monkeypatch):
    """没有显式集成授权时，即使某个测试漏 mock 也不能访问 Docker。"""
    if request.config.getoption("--run-integration"):
        return
    original = subprocess.Popen

    def guarded(args, *positional, **kwargs):
        executable = args.split()[0] if isinstance(args, str) else str(args[0])
        if Path(executable).name.lower() in {
            "docker",
            "docker.exe",
            "docker-compose",
            "docker-compose.exe",
        }:
            pytest.fail("非集成测试禁止真实 Docker 子进程；请修正 mock 的实际消费者")
        return original(args, *positional, **kwargs)

    monkeypatch.setattr(subprocess, "Popen", guarded)


@pytest.fixture(scope="session")
def local_env(request) -> dict[str, str]:
    """真实本地环境绑定;必要时 bootstrap,失败则 skip 集成用例。"""
    if not request.config.getoption("--run-integration"):
        pytest.skip("真实集成未启用；使用 --run-integration 明确授权环境初始化")
    env_file = FRAMEWORK_ROOT / ".local" / "environment.env"
    # 每次核对样例新增字段，真实已有凭据保持不变。
    result = subprocess.run(
        [
            sys.executable,
            "scripts/infra/init.py",
            "--env-file",
            str(env_file),
            "--phase",
            "prepare-local",
        ],
        cwd=FRAMEWORK_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    assert result.returncode == 0, result.stderr
    env = contract.parse_env_file(env_file)
    issues = contract.validate_env(env)
    assert not issues, issues

    if not _compose_running():
        result = subprocess.run(
            [sys.executable, "scripts/infra/up.py", "--env-file", str(env_file)],
            cwd=FRAMEWORK_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        )
        assert result.returncode == 0, result.stderr
    ready = _wait_port(env.values["PG_HOST"], int(env.values["PG_PORT"])) and _wait_port(
        env.values["NACOS_SERVER_ADDR"].split(":")[0],
        int(env.values["NACOS_SERVER_ADDR"].split(":")[1]),
    )
    if not ready:
        pytest.fail("显式集成环境未在超时内就绪，验收失败")
    return env.values


@pytest.fixture()
def isolated_env_factory(local_env, tmp_path):
    """生成隔离 PROJECT_CODE 的环境文件副本 + 对应资源声明,避免污染真实 local 资源。"""

    def _factory(resources_yaml: str | None = None) -> tuple[Path, Path | None]:
        code = f"t02t{uuid.uuid4().hex[:6]}"
        # 同一测试内可能多次调用 factory;文件名带唯一后缀避免相互覆盖
        tag = uuid.uuid4().hex[:6]
        lines = []
        for raw in (
            (FRAMEWORK_ROOT / ".local" / "environment.env").read_text(encoding="utf-8").splitlines()
        ):
            if raw.startswith("PROJECT_CODE="):
                lines.append(f"PROJECT_CODE={code}")
            elif raw.startswith("NACOS_PUBLISH_USER="):
                lines.append(f"NACOS_PUBLISH_USER={code}-local-publisher")
            elif raw.startswith("NACOS_RUNTIME_USER="):
                lines.append(f"NACOS_RUNTIME_USER={code}-local-runtime")
            elif raw.startswith("NACOS_NAMESPACE="):
                lines.append(f"NACOS_NAMESPACE={code}-local")
            elif raw.startswith("APP_ENV="):
                lines.append("APP_ENV=local")
            elif raw.startswith("PG_RESOURCE_PREFIX="):
                continue  # 新项目不能继承旧项目的物理资源前缀
            elif raw.startswith(("RABBIT_HOST=", "RABBIT_ADMIN")):
                continue  # 资源测试不依赖 Rabbit
            else:
                lines.append(raw)
        env_path = tmp_path / f"environment-{tag}.env"
        env_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        res_path = None
        if resources_yaml is not None:
            res_path = tmp_path / f"resources-{tag}.yaml"
            res_path.write_text(resources_yaml, encoding="utf-8")
        return env_path, res_path

    return _factory


@pytest.fixture()
def run_init():
    """执行 scripts/infra/init.py 的辅助函数。"""

    def _run(env_file: Path, resources: Path | None = None):
        cmd = [
            sys.executable,
            "scripts/infra/init.py",
            "--env-file",
            str(env_file),
            "--phase",
            "resources",
        ]
        if resources:
            cmd += ["--resources", str(resources)]
        return subprocess.run(
            cmd,
            cwd=FRAMEWORK_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
            timeout=180,
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        )

    return _run
