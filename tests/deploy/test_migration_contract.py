"""迁移失败恢复与秘密边界；不启动 Docker，不改数据库。"""

import importlib.util
import json
import sys
from pathlib import Path

import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "migration_contract", ROOT / "scripts/deploy/migrate.py"
)
migration = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = migration
spec.loader.exec_module(migration)


def configuration(tmp_path):
    return migration.MigrationConfig(
        target="service",
        tool="alembic",
        db_name="sample_service",
        db_user="sample_migrator",
        password="never-log-this-value",
        owner_role="sample_owner",
        db_host="127.0.0.1",
        db_port=15432,
        jdbc_host="postgres",
        jdbc_port=5432,
        docker_network="sample-local-net",
        migrations_dir=tmp_path,
        state_dir=tmp_path / "state",
        step_timeout=0,
    )


def test_failed_run_retains_deployed_revision_hashes(tmp_path, monkeypatch):
    cfg = configuration(tmp_path)
    previous = {
        "status": "done",
        "facts": {"revision_shas": {"r1": "original"}},
        "steps": [],
    }
    failed = migration.new_run_state(cfg, previous)
    failed["status"] = "failed"
    from contextlib import nullcontext

    monkeypatch.setattr(migration, "open_short_connection", lambda cfg: nullcontext(None))
    monkeypatch.setattr(migration, "read_remote_facts", lambda *args: {"version": "r1"})
    monkeypatch.setattr(migration, "applied_revisions", lambda *args: {"r1"})
    monkeypatch.setattr(
        migration,
        "scan_alembic_versions",
        lambda cfg: {"r1": {"sha256": "changed", "down": None}},
    )
    monkeypatch.setattr(migration, "run_alembic", lambda *args: pytest.fail("篡改后不应运行工具"))
    assert migration.step_validate_alembic(cfg, failed)[0] is False

    monkeypatch.setattr(
        migration,
        "scan_alembic_versions",
        lambda cfg: {"r2": {"sha256": "new", "down": None}},
    )
    assert migration.step_validate_alembic(cfg, failed)[0] is False


def test_nonempty_database_requires_archive_and_ignores_unapplied_hashes(tmp_path, monkeypatch):
    from contextlib import nullcontext

    cfg = configuration(tmp_path)
    monkeypatch.setattr(migration, "open_short_connection", lambda cfg: nullcontext(None))
    monkeypatch.setattr(migration, "read_remote_facts", lambda *args: {"version": "r1"})
    monkeypatch.setattr(migration, "applied_revisions", lambda *args: {"r1"})
    monkeypatch.setattr(
        migration,
        "scan_alembic_versions",
        lambda cfg: {"r1": {"down": None, "sha256": "old"}, "r2": {"down": "r1", "sha256": "new"}},
    )
    monkeypatch.setattr(migration, "run_alembic", lambda *args: (True, "head"))
    assert migration.step_validate_alembic(cfg, None)[0] is False
    assert (
        migration.step_validate_alembic(
            cfg, {"facts": {"revision_shas": {"r1": "old", "r2": "not-applied"}}}
        )[0]
        is True
    )


def test_archive_moves_between_machines_but_cannot_cross_databases(tmp_path):
    cfg = configuration(tmp_path)
    state = migration.new_run_state(cfg, None)
    state.update(status=migration.STATUS_DONE, facts={"revision_shas": {"r1": "old"}})
    migration.save_state(cfg, state)
    archive = next((cfg.state_dir / "history").glob("*.json"))
    cfg.state_dir = tmp_path / "new-machine"
    cfg.previous_state = archive
    assert migration.load_state(cfg)["facts"] == state["facts"]
    cfg.db_name = "different_database"
    with pytest.raises(migration.UsageError, match="不属于"):
        migration.load_state(cfg)


def test_alembic_uses_fixed_image_without_runtime_install(tmp_path, monkeypatch):
    cfg = configuration(tmp_path)
    cfg.migration_image = "registry.invalid/migrate@sha256:" + "a" * 64
    calls = []
    monkeypatch.setattr(migration, "docker_run", lambda *args: calls.append(args) or (0, "ok"))
    assert migration.run_alembic(cfg, ["upgrade", "head"])[0]
    assert calls[0][1] == cfg.migration_image
    assert calls[0][2] == ["upgrade", "head"]
    assert "pip install" not in repr(calls)


def test_timeout_command_and_exception_do_not_contain_password(tmp_path, monkeypatch):
    cfg = configuration(tmp_path)
    assert cfg.password not in repr(cfg)
    calls = []

    class FakeProcess:
        returncode = 0

        def __init__(self, command, **kwargs):
            calls.append(command)
            self.env_file = Path(command[command.index("--env-file") + 1])
            assert cfg.password in self.env_file.read_text(encoding="utf-8")

        def communicate(self, **kwargs):
            return "", ""

        def poll(self):
            return 0

    monkeypatch.setattr(migration.subprocess, "Popen", FakeProcess)
    monkeypatch.setattr(
        migration.subprocess, "run", lambda command, **kwargs: calls.append(command)
    )
    with pytest.raises(migration.StepFailedError) as caught:
        migration.docker_run(cfg, "fake-image", [], [], {"PASSWORD": cfg.password})
    assert cfg.password not in str(caught.value)
    assert cfg.password not in json.dumps(calls)
    assert list(cfg.state_dir.glob(".secret-*")) == []
    assert any(command[:3] == ["docker", "rm", "-f"] for command in calls)


def test_control_connection_loss_prevents_dispatch(tmp_path):
    class BrokenConnection:
        def execute(self, query):
            raise psycopg.OperationalError("connection lost")

    with pytest.raises(migration.ControlLostError):
        migration.control_health_check(BrokenConnection())


def revision_file(root, name, revision, down):
    folder = root / "versions"
    folder.mkdir(exist_ok=True)
    (folder / name).write_text(
        f"revision: str = {revision!r}\ndown_revision: str | tuple[str, ...] | None = {down!r}\n",
        encoding="utf-8",
    )


def test_official_graph_reads_typed_revision_and_merge_parents(tmp_path):
    revision_file(tmp_path, "base.py", "r1", None)
    revision_file(tmp_path, "left.py", "left", "r1")
    revision_file(tmp_path, "right.py", "right", "r1")
    revision_file(tmp_path, "merge.py", "merged", ("left", "right"))
    graph = migration.scan_alembic_versions(configuration(tmp_path))
    assert migration.alembic_head(graph) == "merged"
    assert graph["merged"]["down"] == ("left", "right")
    assert set(graph) == {"r1", "left", "right", "merged"}
    assert all(len(value["sha256"]) == 64 for value in graph.values())


@pytest.mark.parametrize("problem", ["two_heads", "missing_parent", "cycle", "duplicate"])
def test_invalid_version_graph_stops_before_migration(tmp_path, problem):
    revision_file(tmp_path, "first.py", "r1", "r2" if problem == "cycle" else None)
    if problem == "two_heads":
        revision_file(tmp_path, "second.py", "r2", None)
    elif problem == "missing_parent":
        revision_file(tmp_path, "second.py", "r2", "missing")
    elif problem == "cycle":
        revision_file(tmp_path, "second.py", "r2", "r1")
    else:
        revision_file(tmp_path, "second.py", "r1", None)
    with pytest.raises(migration.UsageError):
        migration.scan_alembic_versions(configuration(tmp_path))
