"""仅在脚手架仓库运行：从标准服务样例创建独立业务仓库。"""

from __future__ import annotations

import argparse
import shutil
import tempfile
from pathlib import Path

from common import FRAMEWORK, run, write
from generation import copy_source, service_files
from instructions import service_readme
from registration import replace, replacements, validate_name, validate_package


def create_service(args: argparse.Namespace) -> Path:
    validate_name(args.name)
    validate_package(args.package, args.language)
    project = args.project_root.resolve()
    if (
        not (project / "config/services.yaml").is_file()
        or not (project / "sdk/python/pyproject.toml").is_file()
    ):
        raise ValueError("--project-root 必须是兼容的源码项目")
    output = args.output.resolve()
    if output.exists() or not output.parent.is_dir() or output.is_relative_to(project):
        raise ValueError("独立服务输出须位于主项目之外的不存在目录，父目录必须存在")
    stage = Path(tempfile.mkdtemp(prefix="new-service-"))
    copy_source(project / "sdk/python", stage / "sdk/python")
    copy_source(project / "pom.xml", stage / "pom.xml")
    service_files(stage, args.language, args.name, args.package, None)
    service = stage / "services" / args.name
    values = replacements(args.language, args.name, args.package, None)
    for path in (FRAMEWORK / "config/nacos").glob(f"sample-{args.language}-service*"):
        write(
            service / "config/nacos" / replace(path.name, values),
            replace(path.read_text(encoding="utf-8"), values),
        )
    if args.language == "python":
        run(["uv", "lock", "--project", service], cwd=stage)
        if str(stage) in (service / "uv.lock").read_text(encoding="utf-8"):
            raise ValueError(f"锁包含临时绝对路径，现场保留：{stage}")
    write(service / "README.md", service_readme(args.name, args.language, args.package))
    write(
        service / ".gitignore",
        ".venv/\n__pycache__/\ntarget/\n.pytest_cache/\n.ruff_cache/\n.mypy_cache/\n*.env\n",
    )
    # 不自动 init/commit/push，也不登记宿主运行环境。
    shutil.copytree(service, output)
    print(f"服务源码已生成：{output}；请建立远程并提交，再从主项目运行 add_service.py。")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("java", "python"), required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project-root", type=Path, required=True)
    create_service(parser.parse_args())


if __name__ == "__main__":
    main()
