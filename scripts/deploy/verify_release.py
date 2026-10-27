"""发布前校验固定清单、摘要和所选 Compose 服务；不执行部署、不输出解析后的环境。"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts/config"))
import contract


def rollback_policy(release: dict) -> None:
    if release.get("migration_mode", "compatible") not in {"compatible", "maintenance"}:
        raise ValueError("迁移模式必须是 compatible 或 maintenance")
    hours = release.get("rollback_window_hours")
    if type(hours) is not int or hours <= 0:
        raise ValueError("回退窗口必须为正整数小时")
    try:
        datetime.now(UTC) + timedelta(hours=hours)
    except OverflowError as error:
        raise ValueError("回退窗口超出日期范围") from error
    previous = release.get("compatible_previous_versions")
    if not isinstance(previous, list) or any(
        not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}", value)
        for value in previous
    ):
        raise ValueError("兼容旧发行必须显式列出，首次发行使用空列表")
    for field in ("owner", "approval_reference"):
        value = release.get(field)
        if not isinstance(value, str) or not value.strip() or value.startswith("replace-"):
            raise ValueError("缺少发布负责人或确认记录")


def contained(root: Path, value: str) -> Path:
    if not isinstance(value, str) or Path(value).is_absolute() or ".." in Path(value).parts:
        raise ValueError("发行文件必须为项目内相对路径")
    resolved = (root / value).resolve()
    if not resolved.is_relative_to(root.resolve()) or not resolved.is_file():
        raise ValueError("发行文件不存在或越界")
    return resolved


def validate(root: Path, release: dict, project: str, environment: str) -> None:
    if not isinstance(release, dict):
        raise TypeError("发行清单必须是对象")
    if release.get("project") != project or release.get("environment") != environment:
        raise ValueError("发行与项目环境不匹配")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}", release.get("id", "")):
        raise ValueError("发行编号不合法")
    rollback_policy(release)
    if not isinstance(release.get("files"), dict) or not release["files"]:
        raise ValueError("缺少发行文件摘要")
    for name, digest in release["files"].items():
        path = contained(root, name)
        if (
            not re.fullmatch(r"[a-f0-9]{64}", str(digest))
            or hashlib.sha256(path.read_bytes()).hexdigest() != digest
        ):
            raise ValueError("发行文件摘要不一致")
    if not isinstance(release.get("compose_files"), list) or not release["compose_files"]:
        raise ValueError("缺少项目 Compose 文件")
    for name in release["compose_files"]:
        if name not in release["files"]:
            raise ValueError("Compose 未登记摘要")
    if not isinstance(release.get("images"), dict) or not release["images"]:
        raise ValueError("缺少镜像摘要")
    for name, image in release["images"].items():
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", name) or not re.fullmatch(
            r"[^\s]+@sha256:[a-f0-9]{64}", image
        ):
            raise ValueError("服务器发行只接受含仓库的不可变镜像摘要")
    if not isinstance(release.get("migrations"), list):
        raise TypeError("迁移声明必须是列表，可显式为空")
    for migration in release["migrations"]:
        if not isinstance(migration, dict):
            raise TypeError("迁移声明必须是对象")
        if migration.get("tool") == "alembic" and not re.fullmatch(
            r"[^\s]+@sha256:[a-f0-9]{64}", migration.get("image", "")
        ):
            raise ValueError("Alembic 迁移必须绑定含仓库的固定镜像摘要")
        if migration.get("tool") not in {"flyway", "alembic"} or not re.fullmatch(
            r"[a-z][a-z0-9-]{0,63}", migration.get("target", "")
        ):
            raise ValueError("迁移声明不合法")
        folder = (root / migration["path"]).resolve()
        if not folder.is_relative_to(root.resolve()) or not folder.is_dir():
            raise ValueError("迁移目录越界或缺失")
        for source in folder.rglob("*"):
            if (
                source.is_file()
                and "__pycache__" not in source.parts
                and source.suffix in {".sql", ".py", ".ini"}
                and source.relative_to(root).as_posix() not in release["files"]
            ):
                raise ValueError("迁移文件未登记摘要")


def monitoring_bindings(config: dict, services: list[str], source_env: dict) -> None:
    profiles = source_env.get("COMPOSE_PROFILES", "")
    enabled = "monitoring" in profiles.split(",")
    for name in services:
        item = config.get("services", {}).get(name, {})
        values = item.get("environment", {})
        if name == "api-gateway" and values.get("COMPOSE_PROFILES", "") != profiles:
            raise ValueError("应用 Compose 与组件环境的 COMPOSE_PROFILES 不一致")
        if name == "nginx":
            continue
        port = values.get("MANAGEMENT_SERVER_PORT") or values.get("METRICS_PORT")
        if enabled:
            if (
                not port
                or not str(port).isdigit()
                or not 1 <= int(port) <= 65535
                or str(port) == str(values.get("SERVICE_BIND_PORT"))
            ):
                raise ValueError("启用监控的服务必须配置独立采集端口")
            if values.get("MANAGEMENT_SERVER_PORT") and set(
                values.get("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "").split(",")
            ) != {"health", "prometheus"}:
                raise ValueError("Java 采集端点仅开放 health,prometheus")
        elif port and str(port) not in {"0", "-1"}:
            raise ValueError("关闭监控时仍残留采集监听绑定")


def compose(
    root: Path, release: dict, env_file: Path, services: list[str], source_env: dict | None = None
) -> None:
    if not services or not set(services).issubset(release["images"]):
        raise ValueError("所选应用未登记在发行清单")
    command = [
        "docker",
        "compose",
        "--project-directory",
        str(root),
        "--env-file",
        str(env_file),
    ]
    for name in release["compose_files"]:
        command += ["-f", str(contained(root, name))]
    result = subprocess.run(
        command + ["config", "--format", "json"],
        capture_output=True,
        check=False,
        timeout=30,
    )
    if result.returncode:
        raise ValueError("Compose 校验失败；请在受控终端检查绑定，禁止归档完整环境输出")
    config = json.loads(result.stdout)
    if config.get("name") != f"{release['project']}-{release['environment']}-apps":
        raise ValueError("Compose 项目名未隔离项目与环境")
    if source_env is not None:
        monitoring_bindings(config, services, source_env)
    for name in services:
        item = config.get("services", {}).get(name, {})
        health = item.get("healthcheck", {})
        probe = health.get("test", [])
        if (
            item.get("image") != release["images"][name]
            or item.get("build")
            or not isinstance(probe, list)
            or len(probe) < 2
            or probe[0] not in {"CMD", "CMD-SHELL"}
            or health.get("disable")
        ):
            raise ValueError("所选服务镜像/构建/健康检查与发行不符")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--environment", choices=("test", "prod"), required=True)
    parser.add_argument("--compose-env", type=Path)
    parser.add_argument("--environment-file", type=Path)
    parser.add_argument(
        "--phase",
        choices=("infra", "initialize", "migrate", "deploy"),
        default="deploy",
    )
    parser.add_argument("--service", action="append", default=[])
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        release = yaml.safe_load(args.release.read_text(encoding="utf-8"))
        validate(root, release, args.project, args.environment)
        required = {
            "infra": ["infra/compose.yaml"],
            "initialize": ["infra/resources.yaml"],
            "migrate": ["infra/resources.yaml"],
            "deploy": [],
        }[args.phase]
        if args.phase == "initialize":
            required += [
                p.relative_to(root).as_posix() for p in (root / "config/nacos").glob("*.yaml")
            ]
        if any(path not in release["files"] for path in required):
            raise ValueError("阶段输入未登记摘要")
        network = f"{args.project}-{args.environment}-net"
        if args.environment_file:
            environment = contract.parse_env_file(args.environment_file)
            if (
                contract.validate_env(environment)
                or environment.values.get("PROJECT_CODE") != args.project
                or environment.values.get("APP_ENV") != args.environment
            ):
                raise ValueError("环境文件与发行不一致")
            network = environment.values.get("INFRA_NETWORK", network)
        if not re.fullmatch(r"[a-z][a-z0-9_.-]{0,127}", network):
            raise ValueError("网络名称不合法")
        if args.compose_env:
            compose(
                root,
                release,
                args.compose_env,
                args.service,
                environment.values if args.environment_file else None,
            )
        print(
            json.dumps(
                {
                    "release": release["id"],
                    "project": args.project,
                    "environment": args.environment,
                    "verified": True,
                    "network": network,
                }
            )
        )
        return 0
    except (
        ValueError,
        TypeError,
        KeyError,
        OSError,
        yaml.YAMLError,
        subprocess.SubprocessError,
    ):
        print("发行校验失败；核对项目/环境、摘要、镜像与输入路径")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
