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
    source_files,
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
    return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()


def base_fingerprint(root: Path) -> str:
    """基础镜像输入指纹；业务源码变更不要求重建身份与网关。"""
    digest = hashlib.sha256()
    for name in ("sdk/java", "services/identity-service", "services/api-gateway", ".mvn"):
        for path in sorted(source_files(root / name)):
            digest.update(path.relative_to(root).as_posix().encode())
            digest.update(path.read_bytes())
    for name in ("pom.xml", "mvnw", "mvnw.cmd", ".dockerignore"):
        digest.update(name.encode())
        content = (root / name).read_bytes()
        if name == "pom.xml":
            content = re.sub(rb"<modules>.*?</modules>", b"<modules/>", content, flags=re.S)
        digest.update(content)
    return digest.hexdigest()


def source_identity(root: Path, strict: bool = False, requested: str = "all") -> dict:
    from catalog import select, services

    records = services(root)
    chosen = select(records, requested)
    project_commit = git(root, "rev-parse", "--verify", "HEAD")
    submodules = {}
    directories = [root]
    dirty = bool(git(root, "status", "--porcelain"))
    for name in chosen:
        if records[name]["source"] != "submodule":
            continue
        relative = records[name]["directory"]
        directory = service_directory(root, records[name])
        commit = git(directory, "rev-parse", "HEAD")
        # index 与 HEAD 的 gitlink 都需一致，暂存而未提交不能冒充发行来源。
        staged = git(root, "ls-files", "--stage", "--", relative).split()
        committed = git(root, "ls-tree", "HEAD", "--", relative).split()
        if len(staged) < 2 or staged[0] != "160000" or staged[1] != commit:
            raise ValueError("子仓 checkout 与主仓 gitlink 不一致：" + name)
        if strict and (len(committed) < 3 or committed[2] != commit):
            raise ValueError("子仓 gitlink 尚未提交：" + name)
        dirty = dirty or bool(git(directory, "status", "--porcelain"))
        submodules[relative] = {
            "commit": commit,
            "url": git(directory, "remote", "get-url", "origin"),
        }
        directories.append(directory)
    if strict and dirty:
        raise ValueError("正式部署包要求项目及所选子仓源码已提交且干净")
    if strict:
        for directory in directories:
            remote = git(directory, "remote", "get-url", "origin")
            head = git(directory, "rev-parse", "HEAD")
            run(["git", "fetch", "--no-tags", remote, head], cwd=directory)
    locks = [
        root / "uv.lock",
        *[
            root / records[name]["directory"] / "uv.lock"
            for name in chosen
            if records[name]["language"] == "python"
        ],
    ]
    return {
        "project_commit": project_commit,
        "submodules": submodules,
        "dirty": dirty,
        "lock_files": {
            path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in locks
        },
        "base_fingerprint": base_fingerprint(root),
    }


def copy_inputs(source: Path, target: Path, *, runtime: bool = False) -> None:
    for path in source_files(source):
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
FROM build AS migration-build
RUN uv sync --frozen --no-dev --group migration --no-editable --compile-bytecode
FROM {PYTHON} AS migration
COPY --from=migration-build /build/services/{name}/.venv /opt/venv
COPY --from=build /build/services/{name}/migrations /migrations
ENV PATH="/opt/venv/bin:$PATH"
WORKDIR /migrations
ENTRYPOINT ["/opt/venv/bin/alembic", "-c", "/migrations/alembic.ini"]
FROM {PYTHON} AS runtime
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


def build_service(
    root: Path, name: str, record: dict, tag: str, identity: dict, *, migration: bool = False
) -> str:
    service = service_directory(root, record)
    if record["language"] == "python":
        run(["uv", "lock", "--check", "--project", service], cwd=root)
    with tempfile.TemporaryDirectory(prefix="project-build-") as folder:
        stage = Path(folder)
        copy_inputs(service, stage / "services" / name)
        if record["language"] == "java":
            copy_inputs(root / "sdk/java", stage / "sdk/java")
            pom = (root / "pom.xml").read_text(encoding="utf-8")
            pom = re.sub(
                r"<modules>.*?</modules>",
                f"<modules><module>sdk/java</module><module>services/{name}</module></modules>",
                pom,
                flags=re.S,
            )
            write(stage / "pom.xml", pom)
        else:
            copy_inputs(root / "sdk/python", stage / "sdk/python")
        # 使用服务自有、可审阅的 Dockerfile，暂存目录保持正式项目的相对布局。
        shutil.copyfile(service / "Dockerfile", stage / "Dockerfile")
        run(
            [
                "docker",
                "build",
                "-t",
                tag,
                *(["--target", "migration"] if migration else []),
                "--label",
                "platform.project-commit=" + identity["project_commit"],
                "--label",
                "platform.source-dirty=" + str(identity["dirty"]).lower(),
                stage,
            ],
            cwd=root,
        )
    return subprocess.check_output(
        ["docker", "image", "inspect", "--format", "{{.Id}}", tag], text=True
    ).strip()


def bundle(root: Path, args, config: dict, identity: dict) -> None:
    if not args.images:
        raise ValueError("正式部署包必须提供 --images")
    policy = read_yaml(root / "deploy/release.yaml")
    env = {"APP_ENV": policy.get("environment"), "PROJECT_CODE": config["local"]["project"]}
    if getattr(args, "env_file", None):
        env = load_tool("scripts/config/contract.py").parse_env_file(args.env_file.resolve()).values
    if (
        env.get("APP_ENV") not in {"test", "prod"}
        or env.get("PROJECT_CODE") != config["local"]["project"]
    ):
        raise ValueError("正式环境与项目不匹配")
    images = json.loads(args.images.read_text(encoding="utf-8"))
    applications = {"identity-service", "api-gateway", "nginx", *config["services"]}
    expected = applications | {
        name + "-migration"
        for name, record in config["services"].items()
        if record["language"] == "python"
    }
    if set(images) != expected or any(
        not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", v) for v in images.values()
    ):
        raise ValueError("镜像映射必须完整且全部为 repository@sha256")
    evidence = json.loads(
        Path(getattr(args, "build_record", None) or root / ".local/build-record.json").read_text(
            encoding="utf-8"
        )
    )
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
    load_tool("scripts/deploy/verify_release.py").rollback_policy(policy)
    output = args.output.resolve()
    output.mkdir(parents=True)
    for directory in ("config", "infra", "deploy"):
        copy_inputs(root / directory, output / directory, runtime=True)
        copy_inputs(root / "scripts" / directory, output / "scripts" / directory, runtime=True)
    from catalog import nacos_files

    selected_ids = {
        path.name for path in nacos_files(root, config.get("selected", list(applications)))
    }
    for path in (output / "config/nacos").iterdir():
        if path.suffix in {".yaml", ".json"} and path.name not in selected_ids:
            path.unlink()  # 只清理本次新建部署包中未选服务的复制品。
    for name in ("common.py", "catalog.py", "migrate.py"):
        shutil.copyfile(root / "scripts" / name, output / "scripts" / name)
    for name in ("pyproject.toml", "uv.lock"):
        shutil.copyfile(FRAMEWORK / name, output / name)
    web = args.web_root.resolve() if args.web_root else root / "web"
    if not (web / "index.html").is_file():
        raise ValueError("Web 静态产物缺少 index.html")
    copy_inputs(web, output / "web", runtime=True)
    migrations = [
        {
            "target": "identity-db",
            "tool": "flyway",
            "path": "services/identity-service/src/main/resources/db/migration",
            "image": load_tool("scripts/deploy/migrate.py").FLYWAY_IMAGE,
        }
    ]
    copy_inputs(
        FRAMEWORK / "services/identity-service/src/main/resources/db/migration",
        output / migrations[0]["path"],
    )
    resources = read_yaml(root / "infra/resources.yaml")
    for name, record in config["services"].items():
        service = service_directory(root, record)
        declared_resources = read_yaml(service / "resources.yaml")
        resources["resources"] += declared_resources["resources"]
        write_yaml(output / record["resources"], declared_resources)
        relative = (
            "src/main/resources/db/migration" if record["language"] == "java" else "migrations"
        )
        target = record["migration"]["path"]
        copy_inputs(service / relative, output / target)
        manifest = service / (
            "src/main/resources/permissions.yaml"
            if record["language"] == "java"
            else "src/" + record["package"] + "/resources/permissions.yaml"
        )
        write(
            output / record["permissions"],
            manifest.read_text(encoding="utf-8"),
        )
        migrations.append(
            {
                "target": next(
                    v["resource_id"]
                    for v in declared_resources["resources"]
                    if v["component"] == "postgresql"
                ),
                "tool": "flyway" if record["language"] == "java" else "alembic",
                "path": target,
                **(
                    {"image": images[name + "-migration"]}
                    if record["language"] == "python"
                    else {"image": load_tool("scripts/deploy/migrate.py").FLYWAY_IMAGE}
                ),
            }
        )
    write_yaml(output / "infra/resources.yaml", resources)
    compose = read_yaml(output / "deploy/compose.yaml")
    business = business_compose(config)
    # 项目自有覆盖独立保留，由原生 Compose 合并，不由生成器回写。
    from catalog import services as catalog_services

    selected_catalog = {
        name: record
        for name, record in catalog_services(root).items()
        if name in config.get("selected", applications)
    }
    write_yaml(output / "config/services.yaml", {"services": selected_catalog})
    write(
        output / "config/bootstrap/services.json.example",
        json.dumps(
            {
                "demo": False,
                "web_secret_file": "/run/secrets/web.password",
                "services": [
                    {
                        "name": name,
                        "publisher_secret_file": f"/run/secrets/{name}.password",
                        "resource_secret_file": f"/run/secrets/{name}-resource.password",
                        "gateway_secret_file": f"/run/secrets/{name}-gateway.password",
                    }
                    for name in config["services"]
                ],
            },
            indent=2,
        ),
    )
    write_yaml(
        output / "config/bootstrap/permission-publications.yaml.example",
        {
            "permission_publications": {
                name: {
                    "manifest": record["permissions"],
                    "client_id": "catalog-publisher-"
                    + hashlib.sha256(name.encode()).hexdigest()[:24],
                    "secret_file": f"/etc/{env['PROJECT_CODE']}/{env['APP_ENV']}/identity-bootstrap-secrets/{name}.password",
                }
                for name, record in config["services"].items()
            },
        },
    )
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
    for name in applications:
        compose["services"][name]["image"] = images[name]
    compose["services"]["identity-bootstrap"]["image"] = images["identity-service"]
    write_yaml(output / "deploy/compose.yaml", compose)
    release = policy | {
        "id": args.release,
        "project": env["PROJECT_CODE"],
        "environment": env["APP_ENV"],
        "images": {name: images[name] for name in applications},
        "migrations": migrations,
        "source": identity,
        "complete": bool(evidence.get("complete", True)),
        "compose_files": [
            "deploy/compose.yaml",
            "deploy/compose.server.yaml",
            "deploy/compose.override.yaml",
        ],
    }
    write(
        output / "README.md",
        "# 部署载荷\n\n请按 deploy/ansible/README.md 配置主机、环境、运行秘密与入口，再执行 initialize/migrate/deploy。此载荷通过文件摘要校验，不代表已完成服务器验收。\n",
    )
    release["files"] = {
        p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in output.rglob("*")
        if p.is_file() and p.relative_to(output).as_posix() != "deploy/release.yaml"
    }
    write_yaml(output / "deploy/release.yaml", release)
    load_tool("scripts/deploy/verify_release.py").validate(
        output, release, env["PROJECT_CODE"], env["APP_ENV"]
    )


def build(root: Path, args) -> None:
    from catalog import project_config

    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.release):
        raise ValueError("发行编号不合法")
    if args.output.exists():
        raise ValueError("输出目录已存在，拒绝覆盖")
    config = project_config(root, args.services)
    identity = source_identity(root, strict=True, requested=args.services)
    load_tool("scripts/deploy/verify_release.py").rollback_policy(
        read_yaml(root / "deploy/release.yaml")
    )
    baseline = getattr(args, "base_release", None)
    if args.services != "all" and baseline is None:
        raise ValueError(
            "局部构建必须 --base-release 指向已部署发行清单；首次构建使用 --services all"
        )
    if baseline:
        previous = read_yaml(baseline)
        if (
            previous.get("project") != config["local"]["project"]
            or previous.get("source", {}).get("base_fingerprint") != identity["base_fingerprint"]
        ):
            raise ValueError("基础源码或项目与基线不一致，请完整构建")
        images, tags = {}, {}
        for name in ("identity-service", "api-gateway", "nginx"):
            image = previous["images"][name]
            if not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", image):
                raise ValueError("基线必须提供不可变镜像摘要")
            run(["docker", "pull", image], cwd=root)
            tags[name] = image
            images[name] = subprocess.check_output(
                ["docker", "image", "inspect", "--format", "{{.Id}}", image], text=True
            ).strip()
    else:
        images = base_images(root)
        tags = {
            name: f"local/{config['local']['project']}-{name}:development"
            for name in ("identity-service", "api-gateway")
        }
        tags["nginx"] = NGINX
        images["nginx"] = subprocess.check_output(
            ["docker", "image", "inspect", "--format", "{{.Id}}", NGINX], text=True
        ).strip()
    for name, record in config["services"].items():
        tags[name] = f"local/{config['local']['project']}-{name}:{args.release}"
        images[name] = build_service(root, name, record, tags[name], identity)
        if record["language"] == "python":
            key = name + "-migration"
            tags[key] = f"local/{config['local']['project']}-{key}:{args.release}"
            images[key] = build_service(root, name, record, tags[key], identity, migration=True)
    result = {
        "release": args.release,
        "services": args.services,
        "source": identity,
        "images": images,
        "tags": tags,
        "complete": args.services == "all",
    }
    write(args.output / "build-record.json", json.dumps(result, indent=2))
    print(
        "构建记录："
        + str(args.output / "build-record.json")
        + "；请推送 tags 中镜像并采集 digest 后打包。"
    )


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True)
    parser.add_argument("--services", default="all")
    parser.add_argument(
        "--base-release", type=Path, help="局部构建复用已部署发行的基础镜像，基础输入必须一致"
    )
    parser.add_argument("--output", type=Path, required=True)
    try:
        build(FRAMEWORK, parser.parse_args())
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"构建停止：{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
