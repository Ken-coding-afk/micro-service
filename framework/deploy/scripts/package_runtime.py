"""打包已构建的框架候选制品与运维材料；不构建、不上传、不收集本地秘密。"""

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY_ROOT = ROOT.parent
PROJECT_DOCS = (
    "docs/standards/01-项目编码规范.md",
    "docs/standards/02-Java编码规范.md",
    "docs/standards/03-Python编码规范.md",
    "docs/guides/04-新项目开发与规范升级指南.md",
)
PROJECT_ENTRIES = ("AGENTS.md", "CLAUDE.md", ".cursor/rules/project.mdc")
EXCLUDED = {
    "target",
    "__pycache__",
    ".venv",
    ".local",
    "node_modules",
    "wheels",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    "dist",
    "build",
}


def package(output: Path, wheel: Path, images: dict[str, str]) -> dict:
    if output.exists():
        raise ValueError("发行目录已存在，拒绝覆盖")
    required = [
        ROOT / "foundation/target/foundation-1.0.0-rc.3.jar",
        ROOT / "identity-service/target/identity-service.jar",
        ROOT / "api-gateway/target/api-gateway.jar",
        wheel,
    ]
    development_files = {
        **{name: REPOSITORY_ROOT / name for name in PROJECT_DOCS},
        **{name: REPOSITORY_ROOT / name for name in (".editorconfig", ".gitattributes")},
        **{name: ROOT / "examples" / name for name in PROJECT_ENTRIES},
        "README.md": ROOT / "examples/README.md",
    }
    if not all(path.is_file() for path in [*required, *development_files.values()]):
        raise ValueError("缺少构建制品或开发材料")
    if not {"identity-service", "api-gateway"}.issubset(images):
        raise ValueError("镜像清单缺少基础服务")
    output.mkdir(parents=True)

    def copy(source: Path, name: str):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)

    for folder in ("config", "infra", "deploy", "examples", ".mvn"):
        for source in sorted((ROOT / folder).rglob("*")):
            relative = source.relative_to(ROOT)
            if (
                source.is_file()
                and not EXCLUDED.intersection(relative.parts)
                and not any(part.endswith(".egg-info") for part in relative.parts)
                and (
                    "tests" not in relative.parts or relative.parts[:2] == ("examples", "services")
                )
                and source.suffix != ".pyc"
            ):
                # 发行目录不扫描任意运行环境文件。
                if source.name.endswith((".password", ".pem", ".key", ".env")):
                    raise ValueError("发行源中发现运行秘密文件，拒绝打包")
                name = relative.as_posix()
                if (
                    name.startswith("examples/")
                    and name.removeprefix("examples/") in PROJECT_ENTRIES
                ):
                    continue  # 协作入口只放项目根，避免 examples 内产生第二个规则作用域。
                if name.startswith(("examples/services/", "examples/web/")):
                    name = name.removeprefix("examples/")
                copy(source, name)
    for name in ("pyproject.toml", "uv.lock", "mvnw", "mvnw.cmd"):
        copy(ROOT / name, name)
    for name, source in development_files.items():
        copy(source, name)
    copy(ROOT / "examples/.gitignore", ".gitignore")
    copy(ROOT / "pom.xml", "artifacts/java/platform-parent-1.0.0-rc.3.pom")
    copy(ROOT / "foundation/pom.xml", "artifacts/java/foundation-1.0.0-rc.3.pom")
    for source in required[:3]:
        copy(source, "artifacts/java/" + source.name)
    copy(wheel, "artifacts/python/" + wheel.name)
    # 迁移与正在交付的身份 JAR 同源，防止从工作树另复制一套 SQL。
    with zipfile.ZipFile(required[1]) as jar:
        for name in jar.namelist():
            prefix = "BOOT-INF/classes/db/migration/"
            if name.startswith(prefix) and name.endswith(".sql"):
                target = output / "artifacts/identity-migrations" / Path(name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(name))
    manifest = {
        "status": "candidate",
        "java_version": "1.0.0-rc.3",
        "python_version": "1.0.0rc3",
        "images": images,
        "files": {},
    }
    for path in sorted(output.rglob("*")):
        if path.is_file():
            manifest["files"][path.relative_to(output).as_posix()] = hashlib.sha256(
                path.read_bytes()
            ).hexdigest()
    (output / "release-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    # 基线是发行原件的副本；项目修改运行材料后仍保留它供三方比较。
    (output / "framework-baseline.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sdk-wheel", required=True, type=Path)
    parser.add_argument(
        "--images", required=True, type=Path, help="本次构建的镜像名到不可变引用 JSON"
    )
    args = parser.parse_args()
    try:
        manifest = package(
            args.output.resolve(),
            args.sdk_wheel.resolve(),
            json.loads(args.images.read_text(encoding="utf-8")),
        )
        print(f"已打包 {len(manifest['files'])} 个文件；candidate 不代表验收通过")
        return 0
    except (ValueError, OSError, zipfile.BadZipFile):
        print("打包失败；检查输入与目标目录，未上传任何制品")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
