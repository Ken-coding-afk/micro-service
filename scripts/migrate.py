"""项目级数据库入口；按服务声明复用受锁保护的 Flyway/Alembic 协调器。"""

import argparse
import json

from catalog import services
from common import FRAMEWORK, load_tool


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", choices=("local", "test", "prod"), required=True)
    parser.add_argument("--env-file")
    parser.add_argument("--service", required=True)
    parser.add_argument("--migration-image")
    parser.add_argument("--previous-state")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    record = services(FRAMEWORK).get(args.service)
    if not record or not record.get("migration"):
        parser.error("服务没有迁移声明")
    env_file = args.env_file
    if env_file is None:
        if args.env != "local":
            parser.error("test/prod 必须显式指定包外 --env-file")
        env_file = str(FRAMEWORK / ".local/environment.env")
    from pathlib import Path

    env = load_tool("scripts/config/contract.py").parse_env_file(Path(env_file)).values
    if env.get("APP_ENV") != args.env:
        parser.error("环境文件与 --env 不一致")
    from catalog import database_resource

    target = database_resource(FRAMEWORK, record)["resource_id"]
    command = [
        "--env-file",
        env_file,
        "--target",
        target,
        "--resources",
        str(FRAMEWORK / record["resources"]),
        "--tool",
        record["migration"]["tool"],
        "--migrations-dir",
        str(FRAMEWORK / record["migration"]["path"]),
        "--check" if args.check else "--yes",
    ]
    if record["migration"]["tool"] == "alembic":
        image = args.migration_image
        if not image and args.env == "local":
            image = json.loads(
                (FRAMEWORK / ".local/migration-images.json").read_text(encoding="utf-8")
            )[args.service]
        if not image:
            parser.error("Alembic 必须指定固定 --migration-image")
        command += ["--migration-image", image]
    if args.previous_state:
        command += ["--previous-state", args.previous_state]
    return load_tool("scripts/deploy/migrate.py").main(command)


if __name__ == "__main__":
    raise SystemExit(main())
