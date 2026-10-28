import subprocess
import sys
from pathlib import Path


def test_create_service_invalid_input_has_no_traceback_or_input_echo(tmp_path):
    result = subprocess.run(
        [
            sys.executable,
            "scripts/create_service.py",
            "--language",
            "python",
            "--name",
            "INVALID-sensitive-value",
            "--package",
            "sample",
            "--output",
            str(tmp_path / "out"),
            "--project-root",
            str(tmp_path),
        ],
        cwd=Path(__file__).resolve().parents[2],
        capture_output=True,
    )
    assert result.returncode == 2
    assert b"Traceback" not in result.stderr
    assert b"sensitive-value" not in result.stderr
    assert not (tmp_path / "out").exists()
