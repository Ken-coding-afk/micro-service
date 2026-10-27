"""真实 Git 来源校验；远程获取和 Docker 调用均由替身阻断。"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import build  # noqa: E402
from common import write  # noqa: E402


def test_policy_is_committed_before_build_and_later_changes_require_rebuild(tmp_path, monkeypatch):
    root = tmp_path / "project"
    vendor = root / "vendor/micro-service"
    vendor.mkdir(parents=True)
    hooks = tmp_path / "empty-hooks"
    hooks.mkdir()

    def git(directory, *args):
        return subprocess.check_output(
            ["git", "-c", f"core.hooksPath={hooks}", *args],
            cwd=directory,
            text=True,
            stderr=subprocess.PIPE,
        ).strip()

    def commit(directory):
        git(directory, "add", ".")
        git(
            directory,
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "commit",
            "-qm",
            "fixture",
        )

    git(vendor, "init", "--initial-branch=main")
    write(vendor / "uv.lock", "version = 1\n")
    commit(vendor)
    git(vendor, "remote", "add", "origin", "https://example.invalid/framework.git")
    git(root, "init", "--initial-branch=main")
    write(root / ".gitignore", ".local/\n")
    policy = root / "deploy/release.yaml"
    write(policy, "owner: release-owner\n")
    commit(root)
    git(root, "remote", "add", "origin", "https://example.invalid/project.git")

    class ImageVerificationReached(Exception):
        pass

    calls = []

    def boundary(command, **kwargs):
        calls.append([str(part) for part in command])
        if command[0] == "docker":
            raise ImageVerificationReached
        assert command[:3] == ["git", "fetch", "--no-tags"]

    monkeypatch.setattr(build, "run", boundary)
    source = build.source_identity(root, strict=True)
    assert source["dirty"] is False
    assert len(calls) == 2
    images = {
        name: "registry.invalid/" + name + "@sha256:" + "c" * 64
        for name in ("identity-service", "api-gateway", "nginx")
    }
    write(root / ".local/images.json", json.dumps(images))
    write(root / ".local/build-record.json", json.dumps({"source": source, "images": {}}))
    write(root / ".local/production.env", "PROJECT_CODE=release-project\nAPP_ENV=test\n")
    current = build.source_identity(root, strict=True)
    assert current == source  # 上传后的映射放 .local，不改变构建来源。
    args = argparse.Namespace(
        images=root / ".local/images.json", env_file=root / ".local/production.env"
    )
    config = {"local": {"project": "release-project"}, "services": {}}
    with pytest.raises(ImageVerificationReached):
        build.bundle(root, args, config, current)

    write(policy, "owner: changed-owner\n")
    with pytest.raises(ValueError, match="构建输入已提交"):
        build.source_identity(root, strict=True)
    commit(root)
    changed = build.source_identity(root, strict=True)
    calls.clear()
    with pytest.raises(ValueError, match="构建记录与当前固定源码不一致"):
        build.bundle(root, args, config, changed)
    assert calls == []  # 来源不匹配时不得拉取镜像。
