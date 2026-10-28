"""本地基础服务的状态、日志和停止，不删除数据卷。"""

import argparse
import subprocess
import sys
from pathlib import Path

from common import FRAMEWORK, run


def compose(root: Path, *args: str) -> None:
    run(
        [
            "docker",
            "compose",
            "--project-directory",
            root,
            "--env-file",
            root / ".local/runtime/compose.env",
            "-f",
            root / "deploy/compose.yaml",
            "-f",
            root / ".local/runtime/host.compose.yaml",
            *args,
        ],
        cwd=root,
    )


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
    try:
        main()
    except (ValueError, OSError):
        print("操作失败：请检查输入、路径和文件权限。", file=sys.stderr)
        raise SystemExit(2) from None
    except subprocess.CalledProcessError:
        print("外部命令执行失败，请检查对应工具的脱敏日志。", file=sys.stderr)
        raise SystemExit(1) from None
