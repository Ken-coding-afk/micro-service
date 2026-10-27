"""源码镜像构建与部署载荷；不上传镜像、不远程执行。"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from common import (
    FRAMEWORK,
    IGNORED,
    load_tool,
    read_yaml,
    run,
    service_directory,
    write,
    write_yaml,
)

NGINX = "nginx@sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752"
PYTHON = "python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a"
MAVEN = "maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e"
JRE = (
    "eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99"
)


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def source_identity(root: Path, strict: bool = False) -> dict:
    vendor = root / "vendor/micro-service"
    framework_commit = git(vendor, "rev-parse", "HEAD")
    staged = git(root, "ls-files", "--stage", "vendor/micro-service").split()
    if len(staged) < 2 or staged[0] != "160000" or staged[1] != framework_commit:
        raise ValueError("框架 gitlink 与实际 checkout 不一致；先 git add vendor/micro-service")
    project_commit = git(root, "rev-parse", "--verify", "HEAD")
    dirty = bool(git(root, "status", "--porcelain") or git(vendor, "status", "--porcelain"))
    if strict and dirty:
        raise ValueError("正式部署包要求项目和框架的构建输入已提交")
    if strict:
        for directory in (root, vendor):
            remote = git(directory, "remote", "get-url", "origin")
            head = git(directory, "rev-parse", "HEAD")
            # 从真实远程取回后检查可达性，不用本机缓存当作远程证据。
            run(["git", "fetch", "--no-tags", remote, head], cwd=directory)
    locks = [vendor / "uv.lock", *sorted((root / "services").glob("*/uv.lock"))]
    return {
        "project_commit": project_commit,
        "framework_commit": framework_commit,
        "dirty": dirty,
        "lock_files": {
            path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in locks
        },
    }


def copy_inputs(source: Path, target: Path, *, runtime: bool = False) -> None:
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if IGNORED.intersection(relative.parts) or not path.is_file():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(source.resolve()):
            raise ValueError("构建输入不能通过符号链接越过源目录")
        if path.name.endswith((".password", ".key", ".pem", ".env")):
            raise ValueError(f"源目录含运行秘密文件：{relative}")
        if runtime and ("tests" in relative.parts or path.suffix == ".pyc"):
            continue
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)


def base_images(root: Path) -> dict[str, str]:
    config = read_yaml(root / "config/development.yaml")
    images = {"nginx": NGINX}
    run(["docker", "pull", NGINX], cwd=root)
    for name in ("identity-service", "api-gateway"):
        tag = f"local/{config['local']['project']}-{name}:development"
        run(
            [
                "docker",
                "build",
                "-f",
                FRAMEWORK / "services" / name / "Dockerfile",
                "-t",
                tag,
                FRAMEWORK,
            ],
            cwd=root,
        )
        images[name] = subprocess.check_output(
            ["docker", "image", "inspect", "--format", "{{.Id}}", tag], text=True
        ).strip()
    return images


def business_dockerfile(name: str, record: dict) -> str:
    if record["language"] == "java":
        return f"""FROM {MAVEN} AS build
WORKDIR /build
COPY . .
RUN mvn -B -f pom.xml -pl :{name} -am package -DskipTests
FROM {JRE}
WORKDIR /app
COPY --from=build /build/services/{name}/target/{name}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
"""
    return f"""FROM {PYTHON} AS build
RUN pip install --no-cache-dir uv==0.13.5
WORKDIR /build/services/{name}
COPY . /build
RUN uv lock --check && uv sync --frozen --no-dev --no-editable --compile-bytecode
FROM {PYTHON}
WORKDIR /app
COPY --from=build /build/services/{name}/.venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
ENTRYPOINT ["/opt/venv/bin/python", "-c", "from {record["package"]}.main import main; main()"]
"""


def business_compose(config: dict) -> dict:
    services = {}
    for name, record in config["services"].items():
        probe = (
            [
                "CMD-SHELL",
                'curl -fsS "http://localhost:$${SERVICE_BIND_PORT}/actuator/health/readiness"',
            ]
            if record["language"] == "java"
            else [
                "CMD",
                "python",
                "-c",
                "import os, urllib.request; urllib.request.urlopen('http://localhost:' + os.environ['SERVICE_BIND_PORT'] + '/actuator/health/readiness', timeout=2)",
            ]
        )
        services[name] = {
            "image": f"${{{name.upper().replace('-', '_')}_IMAGE:?指定业务镜像}}",
            "env_file": f"${{RUNTIME_DIR}}/{name}.env",
            "volumes": [f"${{RUNTIME_DIR}}/{name}-secrets:/run/secrets:ro"],
            "networks": ["application"],
            "init": True,
            "restart": "unless-stopped",
            "healthcheck": {
                "test": probe,
                "interval": "5s",
                "timeout": "3s",
                "retries": 20,
                "start_period": "60s",
            },
        }
    return {"services": services}


def build_service(root: Path, name: str, record: dict, tag: str, identity: dict) -> str:
    service = service_directory(root, record)
    if record["language"] == "python":
        run(["uv", "lock", "--check", "--project", service], cwd=root)
    with tempfile.TemporaryDirectory(prefix="project-build-") as folder:
        stage = Path(folder)
        copy_inputs(service, stage / "services" / name)
        fixed = stage / "vendor/micro-service"
        if record["language"] == "java":
            write(
                stage / "pom.xml",
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>build</artifactId><version>1</version><packaging>pom</packaging><modules><module>vendor/micro-service</module><module>services/'
                + name
                + "</module></modules></project>",
            )
            copy_inputs(FRAMEWORK / "java-sdk", fixed / "java-sdk")
            write(fixed / "pom.xml", (FRAMEWORK / "pom.xml").read_text(encoding="utf-8"))
            for core in ("identity-service", "api-gateway"):
                write(
                    fixed / "services" / core / "pom.xml",
                    (FRAMEWORK / "services" / core / "pom.xml").read_text(encoding="utf-8"),
                )
        else:
            copy_inputs(FRAMEWORK / "python-sdk", fixed / "python-sdk")
        write(stage / "Dockerfile", business_dockerfile(name, record))
        run(
            [
                "docker",
                "build",
                "-t",
                tag,
                "--label",
                "platform.project-commit=" + identity["project_commit"],
                "--label",
                "platform.framework-commit=" + identity["framework_commit"],
                stage,
            ],
            cwd=root,
        )
    return subprocess.check_output(
        ["docker", "image", "inspect", "--format", "{{.Id}}", tag], text=True
    ).strip()


def bundle(root: Path, args, config: dict, identity: dict) -> None:
    if not args.images or not args.env_file:
        raise ValueError("正式部署包必须提供 --images 和 --env-file")
    env = load_tool("tools/config/contract.py").parse_env_file(args.env_file.resolve()).values
    if (
        env.get("APP_ENV") not in {"test", "prod"}
        or env.get("PROJECT_CODE") != config["local"]["project"]
    ):
        raise ValueError("正式环境与项目不匹配")
    images = json.loads(args.images.read_text(encoding="utf-8"))
    expected = {"identity-service", "api-gateway", "nginx", *config["services"]}
    if set(images) != expected or any(
        not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", v) for v in images.values()
    ):
        raise ValueError("镜像映射必须完整且全部为 repository@sha256")
    evidence = json.loads((root / ".local/build-record.json").read_text(encoding="utf-8"))
    if evidence["source"] != identity:
        raise ValueError("构建记录与当前固定源码不一致，请从已提交源码重建")
    for name, image in images.items():
        run(["docker", "pull", image], cwd=root)
        actual = subprocess.check_output(
            ["docker", "image", "inspect", "--format", "{{.Id}}", image], text=True
        ).strip()
        if actual != evidence["images"][name]:
            raise ValueError("仓库镜像与本次源码构建镜像不一致：" + name)
    policy = read_yaml(root / "deploy/release.yaml")
    load_tool("tools/deploy/verify_release.py").rollback_policy(policy)
    output = args.output.resolve()
    output.mkdir(parents=True)
    for directory in ("config", "infra", "deploy"):
        copy_inputs(root / directory, output / directory, runtime=True)
        copy_inputs(FRAMEWORK / "tools" / directory, output / "tools" / directory, runtime=True)
    for name in ("pyproject.toml", "uv.lock"):
        shutil.copyfile(FRAMEWORK / name, output / name)
    web = args.web_root.resolve() if args.web_root else root / "web"
    if not (web / "index.html").is_file():
        raise ValueError("Web 静态产物缺少 index.html")
    copy_inputs(web, output / "web", runtime=True)
    migrations = [
        {"target": "identity-db", "tool": "flyway", "path": "artifacts/identity-migrations"}
    ]
    copy_inputs(
        FRAMEWORK / "services/identity-service/src/main/resources/db/migration",
        output / migrations[0]["path"],
    )
    resources = read_yaml(root / "infra/resources.yaml")
    for name, record in config["services"].items():
        service = service_directory(root, record)
        resources["resources"] += read_yaml(service / "resources.yaml")["resources"]
        relative = (
            "src/main/resources/db/migration" if record["language"] == "java" else "migrations"
        )
        target = "artifacts/" + name + "-migrations"
        copy_inputs(service / relative, output / target)
        manifest = service / (
            "src/main/resources/permissions.yaml"
            if record["language"] == "java"
            else "src/" + record["package"] + "/resources/permissions.yaml"
        )
        write(
            output / "artifacts/permissions" / (name + ".yaml"),
            manifest.read_text(encoding="utf-8"),
        )
        migrations.append(
            {
                "target": name + "-db",
                "tool": "flyway" if record["language"] == "java" else "alembic",
                "path": target,
            }
        )
    write_yaml(output / "infra/resources.yaml", resources)
    compose = read_yaml(output / "deploy/compose.yaml")
    business = read_yaml(root / "deploy/business.compose.yaml")
    compose["services"].update(business["services"])
    for section, declaration in business.items():
        if section == "services":
            continue
        if section not in compose:
            compose[section] = declaration
        elif isinstance(compose[section], dict) and isinstance(declaration, dict):
            for name, value in declaration.items():
                if name in compose[section] and compose[section][name] != value:
                    raise ValueError(f"Compose 顶层声明冲突：{section}.{name}")
            compose[section].update(declaration)
        elif compose[section] != declaration:
            raise ValueError(f"Compose 顶层声明冲突：{section}")
    for name, image in images.items():
        compose["services"][name]["image"] = image
    compose["services"]["identity-bootstrap"]["image"] = images["identity-service"]
    write_yaml(output / "deploy/compose.yaml", compose)
    release = policy | {
        "id": args.release,
        "project": env["PROJECT_CODE"],
        "environment": env["APP_ENV"],
        "images": images,
        "migrations": migrations,
        "source": identity,
        "compose_files": ["deploy/compose.yaml", "deploy/compose.server.yaml"],
    }
    write(
        output / "README.md",
        "# 部署载荷\n\n请按 tools/deploy/ansible/README.md 配置主机、环境、运行秘密与入口，再执行 initialize/migrate/deploy。此载荷通过文件摘要校验，不代表已完成服务器验收。\n",
    )
    release["files"] = {
        p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in output.rglob("*")
        if p.is_file() and p.relative_to(output).as_posix() != "deploy/release.yaml"
    }
    write_yaml(output / "deploy/release.yaml", release)
    load_tool("tools/deploy/verify_release.py").validate(
        output, release, env["PROJECT_CODE"], env["APP_ENV"]
    )


def build(root: Path, args) -> None:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.release):
        raise ValueError("发行编号不合法")
    if args.output.exists():
        raise ValueError("输出目录已存在，拒绝覆盖")
    config = read_yaml(root / "config/development.yaml")
    identity = source_identity(root, strict=args.package_only)
    if args.package_only:
        if args.service:
            raise ValueError("局部镜像不能打完整部署包")
        bundle(root, args, config, identity)
        return
    services = (
        {args.service: config["services"][args.service]} if args.service else config["services"]
    )
    images = {} if args.service else base_images(root)
    for name, record in services.items():
        images[name] = build_service(
            root,
            name,
            record,
            f"local/{config['local']['project']}-{name}:{args.release}",
            identity,
        )
    if not args.service:
        images["nginx"] = subprocess.check_output(
            ["docker", "image", "inspect", "--format", "{{.Id}}", NGINX], text=True
        ).strip()
    result = {"source": identity, "images": images, "complete": not bool(args.service)}
    write(args.output / "images.json", json.dumps(result, indent=2))
    if not args.service:
        write(root / ".local/build-record.json", json.dumps(result, indent=2))
