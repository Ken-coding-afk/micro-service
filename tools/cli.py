"""固定框架版本提供的项目操作入口。"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from common import FRAMEWORK, IGNORED, load_tool, maven, read_yaml, run, service_directory, write


def add_service(root: Path, args) -> None:
    from generation import register, service_files, validate_name, validate_package
    from instructions import service_readme

    validate_name(args.name)
    validate_package(args.package, args.language)
    config = read_yaml(root / "config/development.yaml")
    if args.name in config["services"] or (root / "services" / args.name).exists():
        raise ValueError("服务已存在")
    if not 1024 <= args.port <= 65535 or args.port in [
        v["port"] for v in config["services"].values()
    ]:
        raise ValueError("服务端口冲突或不合法")
    # 只暂存本次生成需要的非敏感输入；失败不会留下半个已登记服务。
    controlled = [
        "pom.xml",
        "config/development.yaml",
        "config/nacos/api-gateway.yaml",
        "deploy/business.compose.yaml",
    ]
    before = {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in controlled}
    with tempfile.TemporaryDirectory(prefix="add-service-") as temp:
        stage = Path(temp)
        for path in controlled:
            write(stage / path, (root / path).read_text(encoding="utf-8"))
        shutil.copytree(
            FRAMEWORK / "python-sdk",
            stage / "vendor/micro-service/python-sdk",
            ignore=shutil.ignore_patterns(".venv", "__pycache__", "dist", ".pytest_cache"),
        )
        write(
            stage / "vendor/micro-service/pom.xml",
            (FRAMEWORK / "pom.xml").read_text(encoding="utf-8"),
        )
        service_files(stage, args.language, args.name, args.package, None)
        register(stage, args.language, args.name, args.package, args.port, None)
        write(
            stage / "services" / args.name / "README.md",
            service_readme(args.name, args.language, args.package),
        )
        if any(
            hashlib.sha256((root / p).read_bytes()).hexdigest() != digest
            for p, digest in before.items()
        ):
            raise ValueError("生成期间项目输入被修改，拒绝覆盖")
        destinations = [
            (p, root / p.relative_to(stage))
            for p in stage.rglob("*")
            if p.is_file()
            and p.relative_to(stage).parts[0] != "vendor"
            and not IGNORED.intersection(p.relative_to(stage).parts)
        ]
        for source, target in destinations:
            if target.exists() and target.relative_to(root).as_posix() not in controlled:
                raise ValueError("新增文件与已有文件冲突：" + str(target))
        for source, target in destinations:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    print(
        f"已生成 services/{args.name}；按其中 README 完成资源和授权登记后启动；没有操作运行数据库。"
    )


def dev(root: Path, args) -> int:
    config = read_yaml(root / "config/development.yaml")
    if args.service not in config["services"]:
        raise ValueError("服务未登记")
    record = config["services"][args.service]
    if args.debug and record["language"] != "java":
        raise ValueError("--debug 仅启用 Java JDWP；Python 请用 IDE 选择该服务 .venv 调试入口")
    directory = service_directory(root, record)
    if record["language"] == "java":
        maven(root, "-pl", ":" + args.service, "-am", "package", "-DskipTests")
    else:
        run(["uv", "sync", "--frozen", "--project", directory], cwd=root)
    prepared = load_tool("tools/deploy/dev.py").prepare(
        root,
        args.service,
        root / f".local/runtime/{args.service}.env",
        root / f".local/runtime/{args.service}-host.env",
    )
    cwd, command, child = prepared
    if args.debug and record["language"] == "java":
        command.insert(
            1, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
        )
    process = subprocess.Popen(command, cwd=cwd, env=child)
    try:
        # 真实容器 -> 宿主探测；失败停止当前子进程，不杀占端口的其他进程。
        import httpx
        from local import compose

        deadline = time.monotonic() + 90
        url = f"http://{child['SERVICE_ADVERTISE_HOST']}:{child['SERVICE_ADVERTISE_PORT']}/actuator/health/readiness"
        with httpx.Client(timeout=2, trust_env=False) as client:
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    return process.returncode
                try:
                    if client.get(url).status_code == 200:
                        compose(root, "exec", "-T", "api-gateway", "curl", "-fsS", url)
                        print("业务已就绪，宿主及网关容器均可访问：" + args.service, flush=True)
                        return process.wait()
                except httpx.HTTPError:
                    pass
                time.sleep(1)
        raise ValueError("业务或容器到宿主不通；检查 " + url + " 及防火墙")
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
    parser.add_argument("--project-root", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init")
    init.add_argument("--local", action="store_true", required=True)
    init.add_argument("--dev-host")
    develop = commands.add_parser("dev")
    develop.add_argument("service")
    develop.add_argument("--debug", action="store_true")
    add = commands.add_parser("add-service")
    add.add_argument("--language", choices=("java", "python"), required=True)
    add.add_argument("--name", required=True)
    add.add_argument("--package", required=True)
    add.add_argument("--port", type=int, required=True)
    build = commands.add_parser("build")
    build.add_argument("--release", required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--service")
    build.add_argument("--web-root", type=Path)
    build.add_argument("--images", type=Path)
    build.add_argument("--env-file", type=Path)
    build.add_argument("--package-only", action="store_true")
    args = parser.parse_args()
    root = args.project_root.resolve()
    if (root / "vendor/micro-service").resolve() != FRAMEWORK:
        raise ValueError("只能使用本项目固定 vendor 内的工具")
    try:
        if args.command == "init":
            from local import initialize

            initialize(root, args)
        elif args.command == "add-service":
            add_service(root, args)
        elif args.command == "build":
            from build import build as build_project

            build_project(root, args)
        else:
            return dev(root, args)
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"操作停止：{type(error).__name__}：{error}；已有资源与秘密保留。")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
