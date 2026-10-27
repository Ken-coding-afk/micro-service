"""从根目录公共源码与 services 中的样例生成 Java/Python 混合项目。"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from common import FRAMEWORK, IGNORED, read_yaml, source_files, write, write_yaml
from registration import register, replace, replacements, validate_name, validate_package


def service_files(root: Path, language: str, name: str, package: str, peer: str | None) -> None:
    validate_name(name)
    validate_package(package, language)
    target = root / "services" / name
    if target.exists():
        raise ValueError("服务已存在，拒绝覆盖")
    source = FRAMEWORK / "services" / f"sample-{language}-service"
    values = replacements(language, name, package, peer)
    for path in source_files(source):
        relative = path.relative_to(source)
        if not path.is_file() or IGNORED.intersection(relative.parts):
            continue
        if path.name in {"uv.lock", "uv.toml", "requirements.lock", "Dockerfile"}:
            continue
        text = replace(path.read_text(encoding="utf-8"), values)
        write(target / replace(relative.as_posix(), values), text)
    if language == "java":
        pom = target / "pom.xml"
        text = (
            pom.read_text(encoding="utf-8")
            .replace(
                "<relativePath/>",
                "<relativePath>../../pom.xml</relativePath>",
            )
            .replace("<groupId>com.example</groupId>", f"<groupId>{package}</groupId>")
        )
        write(pom, text)
    from build import business_dockerfile

    write(
        target / "Dockerfile", business_dockerfile(name, {"language": language, "package": package})
    )


def copy_source(source: Path, target: Path) -> None:
    if source.is_symlink():
        raise ValueError("源码导出不接受符号链接")
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(source.stat().st_mode)
    elif source.is_dir():
        for path in source_files(source):
            relative = path.relative_to(source)
            if path.is_file() and not IGNORED.intersection(relative.parts):
                if path.is_symlink() or not path.resolve().is_relative_to(source.resolve()):
                    raise ValueError("源码导出不接受符号链接")
                copy_source(path, target / relative)
    else:
        raise ValueError("快照缺少交付文件：" + str(source))


def generate(args: argparse.Namespace) -> None:
    root = args.root.resolve()
    validate_name(args.project)
    if args.java_service == args.python_service:
        raise ValueError("两服务名称不能相同")
    for language in ("java", "python"):
        name = getattr(args, language + "_service")
        validate_name(name)
        validate_package(getattr(args, language + "_package"), language)
        if len(args.project) + len(name) + 16 > 63:
            raise ValueError("项目与服务名组合超出 PostgreSQL 名称限制")
    if not 0 <= args.port_offset <= 10000:
        raise ValueError("port-offset 必须在 0 到 10000 之间")
    if root.exists() and any(root.iterdir()):
        raise ValueError("生成目标必须为空，不覆盖项目")
    root.mkdir(parents=True, exist_ok=True)
    for name in (
        "sdk",
        "scripts",
        ".mvn",
        "pom.xml",
        "mvnw",
        "mvnw.cmd",
        "pyproject.toml",
        "uv.lock",
        ".editorconfig",
        ".gitattributes",
    ):
        copy_source(FRAMEWORK / name, root / name)
    for core_name in ("identity-service", "api-gateway"):
        copy_source(FRAMEWORK / "services" / core_name, root / "services" / core_name)
    for tool in ("create.py", "create_service.py", "generation.py"):
        if (root / "scripts" / tool).exists():
            (root / "scripts" / tool).unlink()
    for folder in ("config", "infra", "deploy", "web"):
        copy_source(FRAMEWORK / folder, root / folder)
    for path in (root / "config/nacos").glob("sample-*"):
        path.unlink()  # 仅移除刚复制的模板占位配置。
    core = {
        "identity-service": {
            "source": "local",
            "language": "java",
            "directory": "services/identity-service",
            "package": "com.ken.platform.identity",
            "main_class": "com.ken.platform.identity.IdentityApplication",
            "resources": "infra/resources.yaml",
            "migration": {
                "tool": "flyway",
                "path": "services/identity-service/src/main/resources/db/migration",
            },
            "requires": [],
        },
        "api-gateway": {
            "source": "local",
            "language": "java",
            "directory": "services/api-gateway",
            "package": "com.ken.platform.gateway",
            "main_class": "com.ken.platform.gateway.GatewayApplication",
            "requires": ["identity-service"],
        },
    }
    write_yaml(root / "config/services.yaml", {"services": core})
    write_yaml(
        root / "config/development.yaml",
        {"local": {"project": args.project, "port_offset": args.port_offset}, "services": {}},
    )
    gateway_path = root / "config/nacos/api-gateway.yaml"
    gateway = read_yaml(gateway_path)
    gateway["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"] = []
    write_yaml(gateway_path, gateway)
    pom = root / "pom.xml"
    text = pom.read_text(encoding="utf-8")
    text = text.replace("    <module>services/sample-java-service</module>\n", "")
    write(pom, text)
    for language, port in (("java", 48082), ("python", 48083)):
        name, package = getattr(args, language + "_service"), getattr(args, language + "_package")
        peer = args.python_service if language == "java" else args.java_service
        service_files(root, language, name, package, peer)
        register(root, language, name, package, port + args.port_offset, peer)
        from instructions import service_readme

        write(root / "services" / name / "README.md", service_readme(name, language, package))
    mappings = replacements("java", args.java_service, args.java_package, args.python_service)
    mappings |= replacements("python", args.python_service, args.python_package, args.java_service)
    mappings |= {
        'value="java"': f'value="{args.java_service}"',
        'value="python"': f'value="{args.python_service}"',
        "${service}-items": "${service}/items",
    }
    for path in (root / "web").rglob("*"):
        if path.is_file():
            write(path, replace(path.read_text(encoding="utf-8"), mappings))
    for path in (root / "config").rglob("*.example"):
        write(
            path,
            replace(path.read_text(encoding="utf-8"), mappings).replace(
                "sample-project", args.project
            ),
        )
    write(
        root / ".gitignore",
        ".local/\ndist/\n**/.venv/\n**/target/\n**/__pycache__/\n**/.pytest_cache/\n**/.mypy_cache/\n**/.ruff_cache/\n**/node_modules/\n*.env\n!*.env.example\n",
    )
    write(
        root / ".dockerignore",
        ".git\n.local\ndist\n**/.git\n**/.venv\n**/target\n**/__pycache__\n**/node_modules\n",
    )
    copy_source(FRAMEWORK / "docs/standards", root / "docs/standards")
    copy_source(FRAMEWORK / "tests/fixtures", root / "tests/fixtures")
    # 项目只带使用说明和规范，不带框架设计/历史计划。
    for path in (root / "docs/standards").glob("*.md"):
        lines = path.read_text(encoding="utf-8").splitlines()
        write(path, "\n".join(line for line in lines if not line.startswith("目录同步：")) + "\n")
    rules = "文档使用中文；遵循 docs/standards。业务在 services；公共源码在 sdk。变更先明确范围与验证，仅运行受影响测试。秘密不入库。后续业务使用固定 submodule，先提交子仓再提交主仓 gitlink。\n"
    for name in ("AGENTS.md", "CLAUDE.md", ".cursor/rules/project.mdc"):
        write(root / name, rules)
    from catalog import project_config
    from ide import install
    from instructions import readme

    config = project_config(root)
    write(
        root / "README.md",
        readme(config) + f"\n模板来源提交：{getattr(args, 'source_ref', 'unpublished-test')}。\n",
    )
    write(root / "docs/guides/04-新项目开发与规范升级指南.md", readme(config))
    write(
        root / "scripts/README.md",
        "# 项目脚本\n\n从项目根运行；使用根 uv.lock。\n\n"
        "操作步骤见[开发与部署指南](../docs/guides/04-新项目开发与规范升级指南.md)。"
        "init 准备本地依赖，dev 启动业务，compose 管理本地基础容器；"
        "add_service 接入固定子仓，migrate 执行数据库迁移，build/package 生成发行产物。\n",
    )
    install(root, config["services"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "project",
        "java-service",
        "java-package",
        "python-service",
        "python-package",
        "source-ref",
    ):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--port-offset", type=int, default=0)
    generate(parser.parse_args())
