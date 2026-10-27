"""从明确 Git 提交接入业务 submodule；预检完成后才改变宿主文件。"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

from catalog import project_config
from common import FRAMEWORK, read_yaml, run
from ide import install
from registration import register, validate_name, validate_package


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()


def validate_source(source: Path, args: argparse.Namespace) -> None:
    if args.language == "java":
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(source / "pom.xml").getroot()
        if (
            pom.findtext("m:artifactId", namespaces=ns) != args.name
            or pom.findtext("m:groupId", namespaces=ns) != args.package
        ):
            raise ValueError("Java 服务名/包名与远程源码不匹配")
        if pom.findtext("m:parent/m:relativePath", namespaces=ns) != "../../pom.xml":
            raise ValueError("Java 服务必须引用宿主父 POM")
        permission = source / "src/main/resources/permissions.yaml"
    else:
        metadata = tomllib.loads((source / "pyproject.toml").read_text(encoding="utf-8"))
        if metadata["project"]["name"] != args.name:
            raise ValueError("Python 服务名与远程源码不匹配")
        if (
            metadata["project"].get("scripts", {}).get(args.name + "-api")
            != args.package + ".main:main"
        ):
            raise ValueError("Python 入口与指定包名不匹配")
        sdk = metadata["tool"]["uv"]["sources"]["ken-platform-sdk"]
        if sdk.get("path") != "../../sdk/python":
            raise ValueError("Python SDK 路径不符合宿主布局")
        if not (source / "uv.lock").is_file():
            raise ValueError("子仓缺少 uv.lock，请在兼容宿主布局生成并提交后重试")
        permission = source / "src" / args.package / "resources/permissions.yaml"
    if read_yaml(permission).get("service") != args.name:
        raise ValueError("权限目录所属服务不匹配")
    if not (source / f"config/nacos/{args.name}.yaml").is_file():
        raise ValueError("子仓缺少首次接入的 Nacos 配置初始值")
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError("接入源码不接受符号链接")


def add_service(root: Path, args: argparse.Namespace) -> None:
    validate_name(args.name)
    validate_package(args.package, args.language)
    config = project_config(root)
    if args.name in config["services"] or (root / "services" / args.name).exists():
        raise ValueError("服务目录或登记已存在；失败现场请先人工核对，不自动清理")
    if not 1024 <= args.port <= 65535 or args.port in {
        v["port"] for v in config["services"].values()
    }:
        raise ValueError("服务端口冲突或不合法")
    if not args.repo or args.repo.startswith("-") or args.repo.startswith("ext::"):
        raise ValueError("仓库地址不合法")
    git(root, "rev-parse", "--show-toplevel")
    stage = Path(tempfile.mkdtemp(prefix="service-registration-"))
    checkout = stage / "incoming"
    run(["git", "clone", "--no-checkout", "--", args.repo, checkout], cwd=stage)
    commit = git(checkout, "rev-parse", "--verify", "--end-of-options", args.ref + "^{commit}")
    run(["git", "checkout", "--detach", commit], cwd=checkout)
    validate_source(checkout, args)
    host = stage / "host"
    host.mkdir()
    controlled = [
        "pom.xml",
        "config/services.yaml",
        "config/development.yaml",
        "deploy/generated.services.yaml",
    ]
    controlled += [
        p.relative_to(root).as_posix()
        for folder in ("config/nacos", ".vscode")
        for p in (root / folder).glob("*")
        if p.is_file()
    ]
    before = {}
    for relative in controlled:
        source = root / relative
        if source.exists():
            before[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
            destination = host / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    # 仅在同构临时目录校验锁，不重写子仓。
    if args.language == "python":
        from build import copy_inputs

        copy_inputs(root / "sdk/python", host / "sdk/python")
        copy_inputs(checkout, host / "services" / args.name)
        run(["uv", "lock", "--check", "--project", host / "services" / args.name], cwd=host)
    register(host, args.language, args.name, args.package, args.port, None, "submodule", checkout)
    install(host, {args.name: project_config(host)["services"][args.name]})
    pending = [
        p
        for folder in ("config", "deploy", ".vscode")
        for p in (host / folder).rglob("*")
        if p.is_file()
    ]
    pending.append(host / "pom.xml")
    for path in pending:
        relative = path.relative_to(host).as_posix()
        current = root / relative
        if current.exists() and relative not in before:
            raise ValueError("新增文件已存在，拒绝覆盖：" + relative)
        if relative in before and (
            not current.is_file()
            or hashlib.sha256(current.read_bytes()).hexdigest() != before[relative]
        ):
            raise ValueError("预检期间文件被修改：" + relative)
    target = "services/" + args.name
    # Git 使用自身的协议安全策略；不全局允许 file://，不跟踪浮动分支。
    run(["git", "submodule", "add", "--name", args.name, "--", args.repo, target], cwd=root)
    run(["git", "checkout", "--detach", commit], cwd=root / target)
    run(["git", "add", "--", target], cwd=root)
    for path in pending:
        destination = root / path.relative_to(host)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    print(
        f"已接入 {target} @ {commit}；请检查 diff，初始化技术资源并显式分配业务权限；未提交或推送。"
    )
    print(
        "请在 .github/workflows/checks.yml 的对应语言矩阵中登记服务和定向测试；遗漏时 CI 会失败。"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--language", choices=("java", "python"), required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--port", type=int, required=True)
    try:
        add_service(FRAMEWORK, parser.parse_args())
        return 0
    except (
        OSError,
        ValueError,
        KeyError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
    ) as error:
        print(
            f"接入停止：{type(error).__name__}：{error}；请核对 .gitmodules、gitlink、目标目录和预检现场，不自动删除。"
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
