"""从 template 生成 Java/Python 混合项目。"""

from __future__ import annotations

import argparse
import copy
import json
import keyword
import os
import re
import shutil
from pathlib import Path

from common import FRAMEWORK, IGNORED, read_yaml, run, write, write_yaml


def validate_name(name: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name) or len(name) > 30:
        raise ValueError("名称须为不超过 30 字符的小写连字符标识")
    if name in {"identity-service", "api-gateway", "nginx", "con", "nul", "aux", "prn"}:
        raise ValueError("名称与框架或系统保留名称冲突")


def validate_package(package: str, language: str) -> None:
    pattern = r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+" if language == "java" else r"[a-z][a-z0-9_]*"
    if not re.fullmatch(pattern, package) or any(keyword.iskeyword(x) for x in package.split(".")):
        raise ValueError("包名不合法")
    if language == "java" and any(
        x in {"class", "interface", "enum", "package", "import", "public", "void", "int", "new"}
        for x in package.split(".")
    ):
        raise ValueError("Java 包名不能使用关键字")


def replacements(language: str, name: str, package: str, peer: str | None) -> dict[str, str]:
    other = "python" if language == "java" else "java"
    result = {
        f"sample-{language}-service": name,
        f"sample-{language}-db": name + "-db",
        f"sample_{language}": name.replace("-", "_"),
        f"/api/v1/{language}-items": f"/api/v1/{name}/items",
        f"{language}-item:": f"{name}-item:",
        f"{language}.items.": f"{name}.items.",
        f"resource_code: {language}-item": f"resource_code: {name}-item",
    }
    if language == "java":
        result |= {"com.example.sample": package, "com/example/sample": package.replace(".", "/")}
    else:
        result |= {"sample_service": package, "sample-api": name + "-api"}
    if peer:
        result |= {
            f"sample-{other}-service": peer,
            f"/api/v1/{other}-items": f"/api/v1/{peer}/items",
        }
    return result


def replace(text: str, values: dict[str, str]) -> str:
    for old, new in sorted(values.items(), key=lambda pair: -len(pair[0])):
        text = text.replace(old, new)
    return text


def service_files(root: Path, language: str, name: str, package: str, peer: str | None) -> None:
    validate_name(name)
    validate_package(package, language)
    target = root / "services" / name
    if target.exists():
        raise ValueError("服务已存在，拒绝覆盖")
    source = FRAMEWORK / "template/services" / f"{language}-service"
    values = replacements(language, name, package, peer)
    for path in source.rglob("*"):
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
                "<relativePath>../../vendor/micro-service/pom.xml</relativePath>",
            )
            .replace("<groupId>com.example</groupId>", f"<groupId>{package}</groupId>")
        )
        write(pom, text)
        # 包名/路由长度变化会改变 Java 折行；使用项目自身格式器生成合规源码。
        run(
            [FRAMEWORK / ("mvnw.cmd" if os.name == "nt" else "mvnw"), "-f", pom, "spotless:apply"],
            cwd=root,
        )
    else:
        pyproject = target / "pyproject.toml"
        write(
            pyproject,
            pyproject.read_text(encoding="utf-8")
            + '\n[tool.uv.sources]\nken-platform-sdk = { path = "../../vendor/micro-service/python-sdk", editable = true }\n',
        )
        run(["uv", "lock", "--project", target], cwd=root)


def register(
    root: Path, language: str, name: str, package: str, port: int, peer: str | None
) -> None:
    path = root / "config/development.yaml"
    config = read_yaml(path) if path.exists() else {"services": {}}
    if name in config["services"] or port in [v["port"] for v in config["services"].values()]:
        raise ValueError("服务名或端口冲突")
    config["services"][name] = {
        "language": language,
        "directory": f"services/{name}",
        "entry": f"target/{name}.jar" if language == "java" else name + "-api",
        "package": package,
        "port": port,
        "peer": peer,
    }
    from build import business_compose

    compose_path = root / "deploy/business.compose.yaml"
    compose = read_yaml(compose_path) if compose_path.exists() else {"services": {}}
    if not isinstance(compose, dict) or not isinstance(compose.get("services"), dict):
        raise ValueError("business.compose.yaml 必须包含 services 对象")
    if name in compose["services"]:
        raise ValueError("Compose 服务已存在，拒绝覆盖：" + name)
    compose["services"][name] = business_compose({"services": {name: config["services"][name]}})[
        "services"
    ][name]
    write_yaml(path, config)
    write_yaml(compose_path, compose)
    values = replacements(language, name, package, peer)
    for source in (FRAMEWORK / "template/config/nacos").glob(f"sample-{language}-service*"):
        write(
            root / "config/nacos" / replace(source.name, values),
            replace(source.read_text(encoding="utf-8"), values),
        )
    gateway_path = root / "config/nacos/api-gateway.yaml"
    gateway = read_yaml(gateway_path)
    example = read_yaml(FRAMEWORK / "template/config/nacos/api-gateway.yaml")
    route = copy.deepcopy(
        example["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"][
            0 if language == "java" else 1
        ]
    )
    route = json.loads(replace(json.dumps(route), values))
    route["id"] = name + "-items"
    route["predicates"] = [f"Path=/api/v1/{name}/**"]
    gateway["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"].append(route)
    write_yaml(gateway_path, gateway)
    if language == "java":
        pom = root / "pom.xml"
        write(
            pom,
            pom.read_text(encoding="utf-8").replace(
                "</modules>", f"<module>services/{name}</module></modules>"
            ),
        )


LAUNCHER = '''"""项目薄入口；实现与工具依赖跟随固定框架引用。"""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent
framework = root / "vendor/micro-service"
if not (framework / "manage.py").is_file():
    raise SystemExit("框架引用不完整：运行 git submodule update --init --recursive")
raise SystemExit(subprocess.call([
    sys.executable, str(framework / "manage.py"), "--project-root", str(root), *sys.argv[1:]
], cwd=root))
'''


def generate(args: argparse.Namespace) -> None:
    root = args.root.resolve()
    validate_name(args.project)
    if args.java_service == args.python_service:
        raise ValueError("两服务名称不能相同")
    for language in ("java", "python"):
        validate_name(getattr(args, language + "_service"))
        validate_package(getattr(args, language + "_package"), language)
        if len(args.project) + len(getattr(args, language + "_service")) + 16 > 63:
            raise ValueError("项目与服务名组合过长，PostgreSQL 角色名不得超过 63 字符")
    if not 0 <= args.port_offset <= 10000:
        raise ValueError("port-offset 必须在 0 到 10000 之间")
    for folder in ("config", "infra", "deploy"):
        for path in (FRAMEWORK / "template" / folder).rglob("*"):
            relative = path.relative_to(FRAMEWORK / "template")
            if not path.is_file() or IGNORED.intersection(relative.parts):
                continue
            if relative.parts[:2] == ("config", "nacos") and path.name.startswith(
                ("sample-java-service", "sample-python-service")
            ):
                continue  # 服务配置由 register 按实际服务名生成，不复制原名占位文件。
            if (
                path.suffix in {".yaml", ".yml", ".json", ".conf", ".example", ".j2", ".ini"}
                and "tests" not in relative.parts
            ):
                shutil.copyfile(
                    path,
                    (root / relative).parent.mkdir(parents=True, exist_ok=True) or root / relative,
                )
    gateway_path = root / "config/nacos/api-gateway.yaml"
    gateway = read_yaml(FRAMEWORK / "template/config/nacos/api-gateway.yaml")
    gateway["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"] = []
    write_yaml(gateway_path, gateway)
    write_yaml(root / "config/development.yaml", {"services": {}})
    write(
        root / "pom.xml",
        f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>local.project</groupId>
  <artifactId>{args.project}</artifactId><version>0.1.0</version><packaging>pom</packaging>
  <modules><module>vendor/micro-service</module></modules>
</project>
""",
    )
    for language, port in (("java", 48082), ("python", 48083)):
        name, package = getattr(args, language + "_service"), getattr(args, language + "_package")
        peer = args.python_service if language == "java" else args.java_service
        service_files(root, language, name, package, peer)
        register(root, language, name, package, port + args.port_offset, peer)
    config = read_yaml(root / "config/development.yaml")
    config["local"] = {
        "project": args.project,
        "port_offset": args.port_offset,
        "initial_services": [args.java_service, args.python_service],
    }
    write_yaml(root / "config/development.yaml", config)
    mappings = replacements("java", args.java_service, args.java_package, args.python_service)
    mappings |= replacements("python", args.python_service, args.python_package, args.java_service)
    mappings |= {
        'value="java"': f'value="{args.java_service}"',
        'value="python"': f'value="{args.python_service}"',
        "${service}-items": "${service}/items",
    }
    for path in (FRAMEWORK / "template/web").rglob("*"):
        if path.is_file():
            write(
                root / "web" / path.relative_to(FRAMEWORK / "template/web"),
                replace(path.read_text(encoding="utf-8"), mappings),
            )
    write(root / "manage.py", LAUNCHER)
    write(
        root / ".gitignore",
        ".local/\ndist/\n**/.venv/\n**/target/\n**/__pycache__/\n**/.pytest_cache/\n**/.mypy_cache/\n**/.ruff_cache/\n**/node_modules/\n",
    )
    write(
        root / ".dockerignore",
        ".git\n.local\ndist\n**/.git\n**/.venv\n**/target\n**/__pycache__\n**/node_modules\n",
    )
    for name in (".editorconfig", ".gitattributes"):
        source = FRAMEWORK / name
        if source.exists():
            shutil.copyfile(source, root / name)
    rules = "项目文档使用中文。遵循 vendor/micro-service/docs/standards 的三份规范。业务代码在 services，框架改动回到框架仓库；不修改 vendor。变更先说明范围与验收，得到确认后实施；仅跑受影响测试，不运行全仓测试。秘密不得入库。\n"
    for name in ("AGENTS.md", "CLAUDE.md", ".cursor/rules/project.mdc"):
        write(root / name, rules)
    write(
        root / "docs/01-项目开发.md",
        "# 项目开发\n\n从[项目 README](../README.md)开始。通用、Java 和 Python 规范以固定框架的 [standards](../vendor/micro-service/docs/standards/01-项目编码规范.md) 为准；本目录只维护项目自己的补充规则。\n",
    )
    from instructions import readme

    write(root / "README.md", readme(config))
    tasks = [
        {
            "label": "初始化",
            "type": "shell",
            "command": "python manage.py init --local",
            "problemMatcher": [],
        }
    ]
    tasks += [
        {
            "label": "启动 " + name,
            "type": "shell",
            "command": "python manage.py dev " + name,
            "problemMatcher": [],
        }
        for name in config["services"]
    ]
    write(
        root / ".vscode/tasks.json",
        json.dumps({"version": "2.0.0", "tasks": tasks}, ensure_ascii=False, indent=2),
    )
    write(
        root / ".vscode/launch.json",
        json.dumps(
            {
                "version": "0.2.0",
                "configurations": [
                    {
                        "name": "Java 附加",
                        "type": "java",
                        "request": "attach",
                        "hostName": "localhost",
                        "port": 5005,
                    },
                ],
            },
            ensure_ascii=False,
            indent=2,
        ),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("project", "java-service", "java-package", "python-service", "python-package"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--port-offset", type=int, default=0)
    generate(parser.parse_args())
