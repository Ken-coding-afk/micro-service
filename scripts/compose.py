"""本地基础服务的状态、日志和停止，不删除数据卷。"""

import argparse

from common import FRAMEWORK
from local import compose


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("ps", "logs", "stop"))
    parser.add_argument("services", nargs="*")
    args = parser.parse_args()
    allowed = {"identity-service", "api-gateway", "nginx"}
    if any(name not in allowed for name in args.services):
        parser.error("本入口管理本地基础容器；业务由 F5/dev.py 启动，日志在对应终端")
    # 日志显式跟随；不会运行 down -v。
    compose(
        FRAMEWORK,
        args.action,
        *(["--tail", "100", "--follow"] if args.action == "logs" else []),
        *args.services,
    )


if __name__ == "__main__":
    main()
