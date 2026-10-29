"""仅在脚手架仓库运行：从标准样例创建同仓或独立服务。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from common import FRAMEWORK, read_yaml, run, write, write_yaml
from generation import copy_source, service_files, service_readme
from registration import register, replace, replacements, validate_name, validate_package


def create_local_service(args: argparse.Namespace) -> Path:
    """同构预检后写入宿主；失败现场保留，不覆盖现有业务源码。"""
    from catalog import project_config
    from ide import install

    root = args.project_root.resolve()
    config = project_config(root)
    if len(config["local"]["project"]) + len(args.name) + 16 > 63:
        raise ValueError("项目与服务名组合超出PostgreSQL名称限制")
    target = root / "services" / args.name
    if target.exists() or args.name in config["services"]:
        raise ValueError("服务目录或登记已存在")
    if not target.resolve().is_relative_to(root) or (root / "services").is_symlink():
        raise ValueError("服务目录不能越过项目根")
    if args.output is not None or args.port is None:
        raise ValueError("local模式要求--port，禁止--output")
    if not 1024 <= args.port <= 65535 or args.port in {
        record["port"] for record in config["services"].values()
    }:
        raise ValueError("服务端口冲突或不合法")
    stage = Path(tempfile.mkdtemp(prefix="local-service-"))
    before = {}
    controlled = ["pom.xml", ".github/workflows/checks.yml"]
    controlled += [
        path.relative_to(root).as_posix()
        for folder in ("config", ".vscode", "deploy")
        for path in (root / folder).rglob("*")
        if path.is_file()
    ]
    for relative in controlled:
        source = root / relative
        if source.is_symlink() or not source.resolve().is_relative_to(root):
            raise ValueError("宿主文件不能是符号链接或位于项目外")
        before[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
        copy_source(source, stage / relative)
    copy_source(root / "sdk/python", stage / "sdk/python")
    service_files(stage, args.language, args.name, args.package, None)
    service = stage / "services" / args.name
    if args.language == "python":
        run(["uv", "lock", "--project", service], cwd=stage)
        if str(stage) in (service / "uv.lock").read_text(encoding="utf-8"):
            raise ValueError(f"锁包含临时绝对路径，现场保留：{stage}")
    register(stage, args.language, args.name, args.package, args.port, None)
    install(stage, {args.name: project_config(stage)["services"][args.name]})
    workflow = read_yaml(stage / ".github/workflows/checks.yml")
    try:
        entries = workflow["jobs"][args.language]["strategy"]["matrix"]["include"]
    except (KeyError, TypeError):
        raise ValueError("CI矩阵不是受支持的显式include列表，请人工适配后重试") from None
    if not isinstance(entries, list) or any(
        not isinstance(entry, dict) or entry.get("module") == "services/" + args.name
        for entry in entries
    ):
        raise ValueError("CI矩阵不兼容或已登记此服务")
    baseline = read_yaml(FRAMEWORK / ".github/workflows/checks.yml")
    entry = copy.deepcopy(
        next(
            entry
            for entry in baseline["jobs"][args.language]["strategy"]["matrix"]["include"]
            if entry["module"] == f"services/sample-{args.language}-service"
        )
    )
    entry["module"] = "services/" + args.name
    entry["tests"] = replace(
        entry["tests"], replacements(args.language, args.name, args.package, None)
    )
    entries.append(entry)
    write_yaml(stage / ".github/workflows/checks.yml", workflow)
    write(service / "README.md", service_readme(args.name, args.language, args.package, "local"))
    pending = [
        path
        for folder in ("config", "deploy", ".vscode", ".github")
        for path in (stage / folder).rglob("*")
        if path.is_file()
    ] + [stage / "pom.xml"]
    pending = [
        path
        for path in pending
        if before.get(path.relative_to(stage).as_posix())
        != hashlib.sha256(path.read_bytes()).hexdigest()
    ]
    for path in pending:
        relative = path.relative_to(stage).as_posix()
        current = root / relative
        if not current.resolve().is_relative_to(root):
            raise ValueError("写入路径越过项目根")
        actual = hashlib.sha256(current.read_bytes()).hexdigest() if current.exists() else None
        if actual != before.get(relative):
            raise ValueError("预检期间宿主文件变化：" + relative)
    written = []
    destination = target
    try:
        target.mkdir()  # 独占目录；不允许copytree合并既有文件。
        written.append(str(target))
        for path in service.rglob("*"):
            if path.is_file():
                destination = target / path.relative_to(service)
                copy_source(path, destination)
        for path in pending:
            destination = root / path.relative_to(stage)
            copy_source(path, destination)
            written.append(str(destination))
    except OSError:
        print(f"写入失败：{destination}；现场保留：{stage}；已写路径：{written}", file=sys.stderr)
        raise
    print(f"同仓服务已创建：{target}；请初始化技术资源并显式授权；未提交或推送。")
    return target


def create_service(args: argparse.Namespace) -> Path:
    validate_name(args.name)
    validate_package(args.package, args.language)
    if getattr(args, "source", "submodule") == "local":
        return create_local_service(args)
    if getattr(args, "port", None) is not None or args.output is None:
        raise ValueError("独立服务要求--output，禁止--port")
    project = args.project_root.resolve()
    if (
        not (project / "config/services.yaml").is_file()
        or not (project / "sdk/python/pyproject.toml").is_file()
    ):
        raise ValueError("--project-root 必须是兼容的源码项目")
    output = args.output.resolve()
    if output.exists() or not output.parent.is_dir() or output.is_relative_to(project):
        raise ValueError("独立服务输出须位于主项目之外的不存在目录，父目录必须存在")
    stage = Path(tempfile.mkdtemp(prefix="new-service-"))
    copy_source(project / "sdk/python", stage / "sdk/python")
    copy_source(project / "pom.xml", stage / "pom.xml")
    service_files(stage, args.language, args.name, args.package, None)
    service = stage / "services" / args.name
    values = replacements(args.language, args.name, args.package, None)
    for path in (FRAMEWORK / "config/nacos").glob(f"sample-{args.language}-service*"):
        write(
            service / "config/nacos" / replace(path.name, values),
            replace(path.read_text(encoding="utf-8"), values),
        )
    if args.language == "python":
        run(["uv", "lock", "--project", service], cwd=stage)
        if str(stage) in (service / "uv.lock").read_text(encoding="utf-8"):
            raise ValueError(f"锁包含临时绝对路径，现场保留：{stage}")
    write(service / "README.md", service_readme(args.name, args.language, args.package))
    write(
        service / ".gitignore",
        ".venv/\n__pycache__/\ntarget/\n.pytest_cache/\n.ruff_cache/\n.mypy_cache/\n*.env\n",
    )
    # 不自动 init/commit/push，也不登记宿主运行环境。
    shutil.copytree(service, output)
    print(f"服务源码已生成：{output}；请建立远程并提交，再从主项目运行 add_service.py。")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("java", "python"), required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source", choices=("local", "submodule"), default="submodule")
    parser.add_argument("--port", type=int)
    parser.add_argument("--project-root", type=Path, required=True)
    create_service(parser.parse_args())


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(f"操作失败：{error}", file=sys.stderr)
        raise SystemExit(2) from None
    except OSError:
        print("操作失败：请检查输入、路径和文件权限。", file=sys.stderr)
        raise SystemExit(2) from None
    except subprocess.CalledProcessError:
        print("外部命令执行失败，请检查对应工具的脱敏日志。", file=sys.stderr)
        raise SystemExit(1) from None
