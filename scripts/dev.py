"""从项目根启动业务服务，或为 VS Code 准备最小运行环境。"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path

from catalog import project_config
from common import FRAMEWORK, load_tool, maven, run, service_directory, write

HOST_FIELDS = {
    "SERVICE_BIND_HOST",
    "SERVICE_BIND_PORT",
    "SERVICE_ADVERTISE_HOST",
    "SERVICE_ADVERTISE_PORT",
    "PG_HOST",
    "PG_PORT",
    "REDIS_HOST",
    "REDIS_PORT",
    "NACOS_SERVER_ADDR",
    "IDENTITY_INTERNAL_ORIGIN",
    "S3_ENDPOINT",
    "RABBIT_HOST",
    "RABBIT_PORT",
    "NO_PROXY",
}
SYSTEM_FIELDS = {
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "WINDIR",
    "TEMP",
    "TMP",
    "HOME",
    "USERPROFILE",
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
}


def prepare(
    root: Path, service: str, env_file: Path, host_file: Path, *, prepare_only: bool = False
):
    parse_env_file = load_tool("scripts/config/contract.py").parse_env_file
    base = parse_env_file(env_file.resolve()).values
    override = parse_env_file(host_file.resolve()).values
    if base.get("APP_ENV") != "local" or base.get("SERVICE_NAME") != service:
        raise ValueError("仅允许指定服务的 local 运行绑定")
    forbidden = ("ADMIN", "MIGRAT", "PUBLISH", "BOOTSTRAP")
    if any(any(word in key for word in forbidden) for key in base):
        raise ValueError("业务进程不能装载管理/迁移/发布绑定")
    allowed = HOST_FIELDS | {key for key in base if key.endswith("_SECRET_FILE")}
    if set(override) - allowed:
        raise ValueError("宿主覆盖包含未允许字段")
    values = base | override
    if "*" in values.get("NO_PROXY", ""):
        raise ValueError("本地代理例外必须列出明确主机")
    if values.get("NACOS_NAMESPACE") != values.get("PROJECT_CODE", "") + "-local":
        raise ValueError("本地 namespace 与项目不一致")
    for key in ("SERVICE_BIND_PORT", "SERVICE_ADVERTISE_PORT", "PG_PORT"):
        if key in values and (not values[key].isdigit() or not 1 <= int(values[key]) <= 65535):
            raise ValueError("端口不合法")
    for key, value in values.items():
        if key.endswith("_SECRET_FILE") and not Path(value).is_file():
            raise ValueError("本地进程秘密引用不存在")
    from catalog import project_config

    item = project_config(root)["services"][service]
    directory = (root / item["directory"]).resolve()
    if not directory.is_dir():
        raise ValueError("业务目录不存在")
    if item["language"] == "java":
        jar = (directory / item["entry"]).resolve()
        if (
            not jar.is_relative_to(directory)
            or (not prepare_only and not jar.is_file())
            or jar.suffix != ".jar"
        ):
            raise ValueError("先构建登记的业务 JAR")
        command = ["java", "-jar", str(jar)]
    elif item["language"] == "python" and re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_-]*", item["entry"]):
        interpreter = directory / (
            (".venv/Scripts/" + item["entry"] + ".exe")
            if os.name == "nt"
            else (".venv/bin/" + item["entry"])
        )
        if not interpreter.is_file():
            raise ValueError("先安装业务自己的 Python 环境")
        command = [str(interpreter)]
    else:
        raise ValueError("未登记的业务启动类型")
    child = {key: value for key, value in os.environ.items() if key.upper() in SYSTEM_FIELDS}
    return directory, command, child | values


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
    cwd, command, child = prepare(
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
        values = {k: v for k, v in child.items() if k.upper() not in SYSTEM_FIELDS}
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
            "--reload-dir",
            str(directory / "src"),
            "--reload-dir",
            str(root / "sdk/python/src"),
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
