"""按环境显式启动自建组件；external 组件只由资源入口核对，不创建替代容器。"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INPUT_ROOT = ROOT
sys.path.insert(0, str(ROOT / "scripts/config"))
import contract  # noqa: E402


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
        if values.get(key, "self_hosted" if values["APP_ENV"] == "local" else "") == "self_hosted":
            services.append(service)
    if "monitoring" in values.get("COMPOSE_PROFILES", "").split(","):
        services.extend(["prometheus", "grafana"])
    return services


def deployment_targets(catalog: dict, inventory: dict) -> list[dict]:
    hosts = inventory["all"]["children"]["apps"]["hosts"]
    rows = []
    endpoints = set()
    for host in hosts.values():
        address = ipaddress.ip_address(host["ansible_host"])
        if not address.is_private or address.is_loopback or address.is_unspecified:
            raise ValueError("指标采集仅允许部署主机的私网地址")
        for service in host.get("app_services", []):
            if service == "nginx":
                continue
            if service not in catalog:
                raise ValueError("inventory 包含未登记服务")
            port = host.get("metrics_ports", {}).get(service)
            if type(port) is not int or not 1 <= port <= 65535:
                raise ValueError("每个选中服务须声明实际监听的 metrics_ports")
            endpoint = f"[{address}]:{port}" if address.version == 6 else f"{address}:{port}"
            if endpoint in endpoints:
                raise ValueError("指标监听地址重复")
            endpoints.add(endpoint)
            rows.append(
                {
                    "targets": [endpoint],
                    "labels": {
                        "service": service,
                        "__metrics_path__": "/metrics"
                        if catalog[service]["language"] == "python"
                        else "/actuator/prometheus",
                    },
                }
            )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--compose-file", type=Path, default=INPUT_ROOT / "infra/compose.yaml")
    parser.add_argument("--service", action="append", default=[])
    parser.add_argument(
        "--inventory", type=Path, help="监控启用时的部署主机清单；local 使用 init 生成的目标"
    )
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
    if {"grafana", "prometheus"}.intersection(services):
        target = Path(
            env.values.get(
                "MONITORING_TARGETS_FILE",
                child.get(
                    "MONITORING_TARGETS_FILE", str(ROOT / ".local/runtime/monitoring-targets.json")
                ),
            )
        )
        if env.values["APP_ENV"] != "local":
            if args.inventory is None:
                raise ValueError(
                    "部署监控必须提供 --inventory，以所选 app_services 和私网指标端口生成目标"
                )
            import yaml

            inventory = yaml.safe_load(args.inventory.read_text(encoding="utf-8"))
            catalog = yaml.safe_load(
                (INPUT_ROOT / "config/services.yaml").read_text(encoding="utf-8")
            )["services"]
            rows = deployment_targets(catalog, inventory)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(rows, indent=2), encoding="utf-8")
        if not target.is_file() or not isinstance(
            json.loads(target.read_text(encoding="utf-8")), list
        ):
            raise ValueError("监控目标文件缺失或非法；先生成对应环境目标")
        child["MONITORING_TARGETS_FILE"] = str(target.resolve())
    needed = {
        "postgres": ("POSTGRES_ADMIN_PASSWORD",),
        "redis": ("REDIS_PASSWORD",),
        "nacos": ("NACOS_AUTH_TOKEN", "NACOS_AUTH_IDENTITY_VALUE"),
        "rabbitmq": ("RABBIT_ADMIN_PASSWORD",),
    }
    for service in services:
        for key in needed.get(service, ()):
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
