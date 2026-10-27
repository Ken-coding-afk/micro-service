"""项目装配的路径与进程工具；不包含业务运行逻辑。"""

from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import yaml

FRAMEWORK = Path(__file__).resolve().parents[1]
IGNORED = {
    ".git",
    ".venv",
    "target",
    "__pycache__",
    "node_modules",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    "wheels",
    "dist",
    ".local",
}


def run(args: list[str | Path], *, cwd: Path, env: dict | None = None) -> None:
    if str(args[0]) == "uv" and env is None:
        env = os.environ.copy()
        env.pop("UV_PROJECT_ENVIRONMENT", None)  # 业务必须使用自己的环境，不能覆盖工具环境。
    subprocess.run([str(v) for v in args], cwd=cwd, env=env, check=True)


def read_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def write_yaml(path: Path, value: dict) -> None:
    write(path, yaml.safe_dump(value, allow_unicode=True, sort_keys=False))


def load_tool(relative: str):
    """加载固定框架工具；工具自己的 import 根仍是框架，输入显式来自项目。"""
    sys.path.insert(0, str(FRAMEWORK / "tools/config"))
    spec = importlib.util.spec_from_file_location(
        "project_tool_" + hashlib.sha256(relative.encode()).hexdigest()[:12],
        FRAMEWORK / relative,
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def service_directory(root: Path, record: dict) -> Path:
    path = (root / record["directory"]).resolve()
    if not path.is_relative_to((root / "services").resolve()) or not path.is_dir():
        raise ValueError("服务目录必须是本项目 services 内的已有目录")
    return path


def maven(root: Path, *args: str) -> None:
    wrapper = FRAMEWORK / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    run([wrapper, "-f", root / "pom.xml", *args], cwd=root)
