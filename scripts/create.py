"""从本地明确 Git 提交快照导出独立项目；不依赖远程框架 submodule。"""

from __future__ import annotations

import argparse
import io
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path


def create(args: argparse.Namespace) -> Path:
    repository = Path(__file__).resolve().parents[1]
    target = args.output.resolve()
    if target.exists() or not target.parent.is_dir():
        raise ValueError("目标必须不存在且父目录存在；不覆盖任何项目")
    commit = subprocess.check_output(
        ["git", "rev-parse", "--verify", "--end-of-options", args.ref + "^{commit}"],
        cwd=repository,
        text=True,
        encoding="utf-8",
    ).strip()
    archive = subprocess.check_output(["git", "archive", commit], cwd=repository)
    snapshot = Path(tempfile.mkdtemp(prefix="scaffold-source-"))
    with tarfile.open(fileobj=io.BytesIO(archive)) as content:
        content.extractall(snapshot, filter="data")
    generator = snapshot / "scripts/generation.py"
    if not generator.is_file():
        raise ValueError(f"提交不包含源码脚手架创建器：{commit}；快照保留：{snapshot}")
    command = [sys.executable, str(generator), "--root", str(target), "--source-ref", commit]
    for name in (
        "project",
        "java_service",
        "java_package",
        "python_service",
        "python_package",
        "port_offset",
    ):
        command += ["--" + name.replace("_", "-"), str(getattr(args, name))]
    subprocess.run(command, cwd=snapshot, check=True)
    print(f"已从 {commit} 创建 {target}；按 README 准备依赖与初始化；未启动容器或提交。")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--java-service", default="equipment-service")
    parser.add_argument("--java-package", default="com.acme.equipment")
    parser.add_argument("--python-service", default="inspection-service")
    parser.add_argument("--python-package", default="inspection")
    parser.add_argument("--port-offset", type=int, default=0)
    try:
        create(parser.parse_args())
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"创建停止：{error}；现场保留，不覆盖或删除已有目录。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
