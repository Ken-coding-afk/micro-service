"""验证固定构建来源与仓库镜像摘要，生成源码无关的部署包。"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

from build import business_compose, copy_inputs, source_identity
from catalog import project_config
from common import FRAMEWORK, load_tool, read_yaml, run, service_directory, write, write_yaml


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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-record", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--web-root", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("输出目录已存在")
    record = json.loads(args.build_record.read_text(encoding="utf-8"))
    args.release = record["release"]
    config = project_config(FRAMEWORK, record["services"])
    identity = source_identity(FRAMEWORK, strict=True, requested=record["services"])
    bundle(FRAMEWORK, args, config, identity)


if __name__ == "__main__":
    main()
