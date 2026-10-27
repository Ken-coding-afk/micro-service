"""从项目根启动业务服务，或为 VS Code 准备最小运行环境。"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from catalog import project_config
from common import FRAMEWORK, load_tool, maven, run, service_directory, write


def dev(root: Path, args: argparse.Namespace) -> int:
    records = project_config(root)["services"]
    if args.service not in records:
        raise ValueError("请选择已登记的业务服务；基础服务通过 Compose 启动")
    record = records[args.service]
    directory = service_directory(root, record)
    if args.reload and record["language"] != "python":
        raise ValueError("--reload 只适用于 Python")
    if record["language"] == "python":
        run(["uv", "sync", "--frozen", "--project", directory], cwd=root)
    elif not args.prepare_only:
        maven(root, "-pl", ":" + args.service, "-am", "package", "-DskipTests")
    tool = load_tool("scripts/deploy/dev.py")
    cwd, command, child = tool.prepare(
        root,
        args.service,
        root / f".local/runtime/{args.service}.env",
        root / f".local/runtime/{args.service}-host.env",
        prepare_only=args.prepare_only,
    )
    if args.tenant_code:
        import re

        if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", args.tenant_code):
            raise ValueError("租户编码不合法")
        child["SWAGGER_TENANT_CODE"] = args.tenant_code
    if args.prepare_only:
        # 仅写经过验证的 runtime 键；不把 shell 管理凭据传给调试进程。
        values = {k: v for k, v in child.items() if k.upper() not in tool.SYSTEM_FIELDS}
        if any("\n" in v or "\r" in v for v in values.values()):
            raise ValueError("运行变量不得包含换行")
        destination = root / f".local/runtime/{args.service}-ide.env"
        write(destination, "".join(f"{k}={v}\n" for k, v in values.items()))
        destination.chmod(0o600)
        print("调试环境已准备：" + args.service)
        return 0
    if args.reload:
        import os

        interpreter = directory / (
            ".venv/Scripts/python.exe" if os.name == "nt" else ".venv/bin/python"
        )
        command = [
            str(interpreter),
            "-m",
            "uvicorn",
            record["app_factory"],
            "--factory",
            "--loop",
            "ken_platform_sdk.db:database_loop_factory",
            "--reload",
            "--host",
            child["SERVICE_BIND_HOST"],
            "--port",
            child["SERVICE_BIND_PORT"],
        ]
    process = subprocess.Popen(command, cwd=cwd, env=child)
    try:
        return process.wait()
    except KeyboardInterrupt:
        return 130
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--reload", action="store_true")
    parser.add_argument(
        "--tenant-code", default="", help="Swagger 授权时明确选择的租户编码，不改变登录身份或授权"
    )
    try:
        return dev(FRAMEWORK, parser.parse_args())
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"启动停止：{error}；先按 README 初始化，不自动迁移数据库或创建账号。")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
