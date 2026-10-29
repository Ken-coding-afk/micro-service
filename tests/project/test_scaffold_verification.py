"""验收入口的失败报告和真实子进程生命周期；不启动Docker。"""

import argparse
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from verify_scaffold import Verification


def verification(tmp_path):
    return Verification(
        argparse.Namespace(
            output=tmp_path / "candidate",
            project="verify-fixture",
            port_offset=5000,
            dev_host="127.0.0.1",
            ref="HEAD",
        )
    )


def test_existing_directory_never_changes_or_calls_docker(tmp_path):
    check = verification(tmp_path)
    check.root.mkdir()
    sentinel = check.root / "keep"
    sentinel.write_text("unchanged")
    assert check.run() == 1
    assert sentinel.read_text() == "unchanged"
    assert check.report["phases"]["preflight"]["status"] == "failed"
    assert check.report["phases"]["generate"]["status"] == "not_run"
    assert not (check.root / ".local").exists()


def test_command_failure_and_timeout_are_real_process_results(tmp_path):
    check = verification(tmp_path)
    with pytest.raises(subprocess.CalledProcessError):
        check.command([sys.executable, "-c", "raise SystemExit(7)"], tmp_path)
    with pytest.raises(subprocess.TimeoutExpired):
        check.command([sys.executable, "-c", "import time; time.sleep(30)"], tmp_path, 1)


def test_failed_phase_records_failure_not_success(tmp_path):
    check = verification(tmp_path)
    with pytest.raises(subprocess.CalledProcessError):
        check.phase(
            "smoke",
            lambda: check.command(
                [sys.executable, "-c", "raise SystemExit(4)"],
                tmp_path,
            ),
        )
    assert check.report["phases"]["smoke"]["status"] == "failed"
    assert check.report["phases"]["smoke"]["exit_code"] == 4
    check.phase("cleanup", check.cleanup)
    assert check.report["status"] == "failed"
