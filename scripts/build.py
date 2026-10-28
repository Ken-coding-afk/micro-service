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
    load_tool,
    read_yaml,
    run,
    service_directory,
    source_files,
    write,
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
        if not path.is_file():
            continue
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
ENTRYPOINT ["/opt/venv/bin/python", "-m", "alembic", "-c", "/migrations/alembic.ini"]
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
                'curl -fsS "http://localhost:$${SERVICE_BIND_PORT}/readyz"',
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
