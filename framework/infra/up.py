"""按环境显式启动自建组件；external 组件只由资源入口核对，不创建替代容器。"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "config"))
import contract


def selected_services(values: dict[str, str]) -> list[str]:
    services = []
    for key, service in (
        ("PG_MODE", "postgres"),
        ("REDIS_MODE", "redis"),
        ("NACOS_MODE", "nacos"),
        ("RABBIT_MODE", "rabbitmq"),
    ):
        if service == "rabbitmq" and not values.get("RABBIT_HOST"):
            continue
        if (
            values.get(key, "self_hosted" if values["APP_ENV"] == "local" else "")
            == "self_hosted"
        ):
            services.append(service)
    return services


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True)
    parser.add_argument(
        "--compose-file", type=Path, default=ROOT / "infra/compose.yaml"
    )
    parser.add_argument("--service", action="append", default=[])
    args = parser.parse_args()
    path = Path(args.env_file).resolve()
    env = contract.parse_env_file(path)
    issues = contract.validate_env(env)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 2
    services = selected_services(env.values)
    if args.service:
        if not set(args.service).issubset(services):
            print("拒绝启动未声明为 self_hosted 的组件", file=sys.stderr)
            return 2
        services = list(dict.fromkeys(args.service))
    if not services:
        print("所有已启用组件均为 external，不启动容器")
        return 0
    child = os.environ.copy()
    needed = {
        "postgres": ("POSTGRES_ADMIN_PASSWORD",),
        "redis": ("REDIS_PASSWORD",),
        "nacos": ("NACOS_AUTH_TOKEN", "NACOS_AUTH_IDENTITY_VALUE"),
        "rabbitmq": ("RABBIT_ADMIN_PASSWORD",),
    }
    for service in services:
        for key in needed[service]:
            child[key] = contract.secret_value(env.values, key)
    return subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            str(path),
            "-f",
            str(args.compose_file.resolve()),
            "up",
            "-d",
            "--wait",
            *services,
        ],
        cwd=ROOT,
        check=False,
        env=child,
    ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
