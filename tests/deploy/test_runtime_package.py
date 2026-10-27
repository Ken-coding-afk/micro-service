"""退役制品复制器后，验证源码部署载荷的输入边界。"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from build import copy_inputs  # noqa: E402
from common import write  # noqa: E402


def test_package_can_leave_the_source_checkout(tmp_path):
    source = tmp_path / "source"
    write(source / "config/app.yaml", "name: project\n")
    copy_inputs(source, tmp_path / "bundle", runtime=True)
    (source / "config/app.yaml").unlink()
    assert (tmp_path / "bundle/config/app.yaml").read_text() == "name: project\n"


def test_package_rejects_accidental_secret(tmp_path):
    write(tmp_path / "source/leak.password", "must-not-copy")
    with pytest.raises(ValueError, match="秘密"):
        copy_inputs(tmp_path / "source", tmp_path / "bundle", runtime=True)
    assert not (tmp_path / "bundle/leak.password").exists()


def test_runtime_package_excludes_development_material(tmp_path):
    source = tmp_path / "source"
    write(source / "tool.py", "pass\n")
    for name in (
        "tests/test_tool.py",
        ".local/private.txt",
        "__pycache__/tool.pyc",
        ".venv/marker",
    ):
        write(source / name, "development-only")
    copy_inputs(source, tmp_path / "bundle", runtime=True)
    assert [
        p.relative_to(tmp_path / "bundle").as_posix()
        for p in (tmp_path / "bundle").rglob("*")
        if p.is_file()
    ] == ["tool.py"]
