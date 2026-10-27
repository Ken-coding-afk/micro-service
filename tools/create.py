"""从可获取的固定 Git 版本创建独立混合项目；本入口仅使用标准库。"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def create(args: argparse.Namespace) -> Path:
    target = args.output.resolve()
    if target.exists():
        raise ValueError("目标必须不存在；不会覆盖已有目录")
    if not target.parent.is_dir():
        raise ValueError("目标父目录必须存在")
    remote = (
        args.remote
        or subprocess.check_output(
            ["git", "remote", "get-url", "origin"],
            cwd=Path(__file__).resolve().parents[1],
            text=True,
        ).strip()
    )
    stage = Path(tempfile.mkdtemp(prefix=target.name + "-creating-", dir=target.parent))
    print(f"生成目录：{stage}", flush=True)
    subprocess.run(["git", "init", "--initial-branch=main", str(stage)], check=True)
    subprocess.run(["git", "config", "core.longpaths", "true"], cwd=stage, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "core.longpaths=true",
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            "--",
            remote,
            "vendor/micro-service",
        ],
        cwd=stage,
        check=True,
    )
    vendor = stage / "vendor/micro-service"
    subprocess.run(["git", "config", "core.longpaths", "true"], cwd=vendor, check=True)
    commit = subprocess.check_output(
        ["git", "rev-parse", "--verify", args.ref + "^{commit}"],
        cwd=vendor,
        text=True,
    ).strip()
    subprocess.run(["git", "checkout", "--detach", commit], cwd=vendor, check=True)
    subprocess.run(["git", "add", "vendor/micro-service"], cwd=stage, check=True)
    tool = vendor / "tools/generation.py"
    if not tool.is_file():
        raise ValueError(f"目标版本不包含项目创建器；保留生成目录：{stage}")
    env = os.environ | {"UV_PROJECT_ENVIRONMENT": str(stage / ".local/tools-venv")}
    subprocess.run(
        [
            "uv",
            "run",
            "--frozen",
            "--project",
            str(vendor),
            "python",
            str(tool),
            "--root",
            str(stage),
            "--project",
            args.project,
            "--java-service",
            args.java_service,
            "--java-package",
            args.java_package,
            "--python-service",
            args.python_service,
            "--python-package",
            args.python_package,
            "--port-offset",
            str(args.port_offset),
        ],
        env=env,
        check=True,
    )
    stage.rename(target)
    print(f"项目已创建：{target}\n下一步：cd {target.name}；阅读 README.md")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--remote")
    parser.add_argument("--project", required=True)
    parser.add_argument("--java-service", default="document-service")
    parser.add_argument("--java-package", default="com.example.document")
    parser.add_argument("--python-service", default="analysis-service")
    parser.add_argument("--python-package", default="analysis")
    parser.add_argument("--port-offset", type=int, default=0)
    try:
        create(parser.parse_args())
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"创建失败，已生成目录保留供检查：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
