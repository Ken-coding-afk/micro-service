"""发行包含运行所需文件，并拒绝已有输出和混入的运行秘密。"""

import hashlib
import importlib.util
import zipfile
import subprocess
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "runtime_package", Path(__file__).parents[1] / "scripts/package_runtime.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def sources(root):
    for name in (
        "foundation/target/foundation-1.0.0-rc.2.jar",
        "foundation/pom.xml",
        "api-gateway/target/api-gateway.jar",
        "pom.xml",
        "pyproject.toml",
        "uv.lock",
        "mvnw",
        "mvnw.cmd",
        "config/contract.py",
        "config/environments/local.env.example",
        "infra/resources.yaml",
        "deploy/compose.yaml",
        "examples/services/sample-java-service/pom.xml",
        "examples/services/sample-python-service/pyproject.toml",
        "examples/web/index.html",
        "examples/.gitignore",
        ".mvn/wrapper/maven-wrapper.properties",
        "wheel.whl",
        "examples/services/sample-java-service/target/ignored.jar",
        "config/__pycache__/ignored.pyc",
        "config/tests/test_ignored.py",
    ):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(name, encoding="utf-8")
    (root / "examples/.gitignore").write_bytes(
        (release.ROOT / "examples/.gitignore").read_bytes()
    )
    jar = root / "identity-service/target/identity-service.jar"
    jar.parent.mkdir(parents=True)
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("BOOT-INF/classes/db/migration/V1__identity.sql", "SELECT 1;")


def test_package_can_leave_the_source_checkout(tmp_path, monkeypatch):
    source = tmp_path / "source"
    sources(source)
    monkeypatch.setattr(release, "ROOT", source)
    output = tmp_path / "release"
    manifest = release.package(
        output,
        source / "wheel.whl",
        {"identity-service": "sha256:fixture", "api-gateway": "sha256:fixture"},
    )
    assert (
        output / "artifacts/identity-migrations/V1__identity.sql"
    ).read_text() == "SELECT 1;"
    assert (output / "pyproject.toml").is_file()
    assert (output / "uv.lock").is_file()
    assert (output / "framework-baseline.json").read_bytes() == (
        output / "release-manifest.json"
    ).read_bytes()
    assert (output / "services/sample-java-service/pom.xml").is_file()
    assert (output / "services/sample-python-service/pyproject.toml").is_file()
    assert (output / "web/index.html").is_file()
    assert not (output / "examples/services").exists()
    subprocess.run(["git", "init", str(output)], check=True, capture_output=True)
    private = [
        ".local/environment.env",
        ".local/runtime/compose.env",
        "services/sample-python-service/.venv/pyvenv.cfg",
        "services/sample-java-service/target/private.jar",
    ]
    ignored = subprocess.run(
        ["git", "-C", str(output), "check-ignore", *private],
        check=True,
        capture_output=True,
        text=True,
    )
    assert ignored.stdout.splitlines() == private
    visible = subprocess.run(
        [
            "git",
            "-C",
            str(output),
            "check-ignore",
            "services/sample-java-service/pom.xml",
            "config/environments/local.env.example",
        ],
        capture_output=True,
    )
    assert visible.returncode == 1
    assert not any("ignored" in name for name in manifest["files"])
    assert not (output / "identity-service/src").exists()
    for name, digest in manifest["files"].items():
        assert hashlib.sha256((output / name).read_bytes()).hexdigest() == digest
    with pytest.raises(ValueError, match="拒绝覆盖"):
        release.package(output, source / "wheel.whl", {})


def test_package_rejects_accidental_secret(tmp_path, monkeypatch):
    sources(tmp_path)
    monkeypatch.setattr(release, "ROOT", tmp_path)
    (tmp_path / "deploy/database.password").write_text("do-not-publish")
    with pytest.raises(ValueError, match="秘密文件"):
        release.package(
            tmp_path / "output",
            tmp_path / "wheel.whl",
            {"identity-service": "fixture", "api-gateway": "fixture"},
        )
    assert not (tmp_path / "output/deploy/database.password").exists()
