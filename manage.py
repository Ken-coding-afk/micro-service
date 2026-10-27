"""框架统一入口：创建项目，或为指定项目调用固定版本工具。"""

import argparse
import os
import subprocess
import sys
from pathlib import Path


def main() -> int:
    framework = Path(__file__).resolve().parent
    if len(sys.argv) > 1 and sys.argv[1] == "create":
        return subprocess.call([sys.executable, str(framework / "tools/create.py"), *sys.argv[2:]])
    parser = argparse.ArgumentParser(
        description="创建：python manage.py create --help；业务项目：python manage.py {init,dev,add-service,build}",
        add_help=False,
    )
    parser.add_argument("--project-root", type=Path, required=True, help="业务项目根目录")
    if len(sys.argv) == 1 or ("--help" in sys.argv and "--project-root" not in sys.argv):
        parser.print_help()
        return 0
    args, remaining = parser.parse_known_args()
    project = args.project_root.resolve()
    env = os.environ | {"UV_PROJECT_ENVIRONMENT": str(project / ".local/tools-venv")}
    return subprocess.call(
        [
            "uv",
            "run",
            "--frozen",
            "--project",
            str(framework),
            "python",
            str(framework / "tools/cli.py"),
            "--project-root",
            str(project),
            *remaining,
        ],
        cwd=project,
        env=env,
    )


if __name__ == "__main__":
    raise SystemExit(main())
