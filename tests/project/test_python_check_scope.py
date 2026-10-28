import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from check_python import changed_files, grouped_files


def test_git_scope_tracks_renames_deletes_and_unicode_paths(tmp_path):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=tmp_path).decode().strip()

    git("init", "-q")
    git("config", "user.name", "Test")
    git("config", "user.email", "test@example.invalid")
    (tmp_path / "pyproject.toml").write_text("[project]\nname='fixture'\n")
    old = tmp_path / "old.py"
    old.write_text("x = 1\n")
    (tmp_path / "deleted.py").write_text("x = 2\n")
    git("add", ".")
    git("commit", "-qm", "base")
    base = git("rev-parse", "HEAD")
    old.rename(tmp_path / "中文 space.py")
    (tmp_path / "deleted.py").unlink()
    (tmp_path / "module").mkdir()
    (tmp_path / "module/pyproject.toml").write_text("[project]\nname='module'\n")
    (tmp_path / "module/a.py").write_text("y = 1\n")
    git("add", "-A")
    git("commit", "-qm", "change")
    files = changed_files(tmp_path, base, "HEAD")
    groups = grouped_files(tmp_path, files)
    assert groups == {
        tmp_path / "module": [tmp_path / "module/a.py"],
        tmp_path: [tmp_path / "中文 space.py"],
    }
    assert grouped_files(tmp_path, changed_files(tmp_path, "0" * 40, "HEAD")) == groups


def test_paths_cannot_escape_project_and_no_implicit_full_scan(tmp_path):
    with pytest.raises(ValueError, match="项目内"):
        grouped_files(tmp_path, [tmp_path.parent / "outside.py"])
    result = subprocess.run(
        [sys.executable, "scripts/check_python.py"],
        capture_output=True,
        cwd=Path(__file__).resolve().parents[2],
    )
    assert result.returncode == 2
