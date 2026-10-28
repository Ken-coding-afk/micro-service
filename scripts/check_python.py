"""按显式变更范围调用各项目已有的 Ruff，不默认扫描整个工作区。"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path


def changed_files(root: Path, base: str, head: str) -> list[Path]:
    if base and set(base) == {"0"}:
        command = ["git", "ls-tree", "-rz", "--name-only", head]
    else:
        command = ["git", "diff", "--name-only", "-z", "--diff-filter=ACMRT", base, head, "--"]
    result = subprocess.check_output(command, cwd=root)
    return [root / name.decode("utf-8") for name in result.split(b"\0") if name]


def grouped_files(root: Path, paths: list[Path]) -> dict[Path, list[Path]]:
    groups: dict[Path, list[Path]] = {}
    root = root.resolve()
    for path in sorted(set(paths)):
        path = path.resolve()
        if not path.is_relative_to(root):
            raise ValueError("检查路径必须位于项目内")
        if path.suffix != ".py" or not path.is_file():
            continue
        project = path.parent
        while not (project / "pyproject.toml").is_file() and project != root:
            project = project.parent
        if not (project / "pyproject.toml").is_file():
            raise ValueError("检查路径缺少 pyproject.toml")
        groups.setdefault(project, []).append(path)
    return groups


def check(root: Path, paths: list[Path]) -> None:
    for project, files in grouped_files(root, paths).items():
        prefix = ["uv", "run", "--frozen", "--project", str(project), "ruff"]
        subprocess.run(
            [
                *prefix,
                "check",
                "--select",
                "E4,E7,E9,F,I",
                "--target-version",
                "py312",
                *map(str, files),
            ],
            cwd=project,
            check=True,
        )
        subprocess.run([*prefix, "format", "--check", *map(str, files)], cwd=project, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    scope = parser.add_mutually_exclusive_group(required=True)
    scope.add_argument("--base")
    scope.add_argument("--paths", nargs="+", type=Path)
    parser.add_argument("--head")
    args = parser.parse_args()
    if bool(args.base) != bool(args.head):
        parser.error("--base 和 --head 必须一起提供")
    root = Path(__file__).resolve().parents[1]
    paths = changed_files(root, args.base, args.head) if args.base else args.paths
    check(root, paths)


if __name__ == "__main__":
    main()
