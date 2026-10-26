"""配置字段归属合同与发布漂移测试(计划 T-02 → AC-18)。

单元部分不依赖组件;漂移部分需要真实 Nacos(设计 02·配置发布)。
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from config import contract  # noqa: E402

FRAMEWORK_ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# 单元:字段归属与来源校验
# ---------------------------------------------------------------------------

def test_unknown_env_field_rejected(tmp_path):
    env_file = tmp_path / "environment.env"
    env_file.write_text("PROJECT_CODE=doc-insight\nAPP_ENV=local\n"
                        "NOT_A_FIELD=1\n", encoding="utf-8")
    issues = contract.validate_env(contract.parse_env_file(env_file))
    assert any("未知环境字段" in i and "NOT_A_FIELD" in i for i in issues)


def test_missing_required_env_field_reported(tmp_path):
    env_file = tmp_path / "environment.env"
    env_file.write_text("PROJECT_CODE=doc-insight\n", encoding="utf-8")
    issues = contract.validate_env(contract.parse_env_file(env_file))
    assert any("缺少必填环境字段: APP_ENV" in i for i in issues)


def test_nacos_source_rejects_env_binding_keys(tmp_path):
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8")
    (source / "identity-service.yaml").write_text(
        "pg_host: 127.0.0.1\n", encoding="utf-8")
    issues = contract.validate_nacos_source(source)
    assert any("禁止进入 Nacos" in i for i in issues)


def test_nacos_service_override_only_allowlisted(tmp_path):
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "allow_override:\n  - log_level\nlog_level: INFO\nhttp:\n"
        "  connect_timeout_seconds: 2\n", encoding="utf-8")
    (source / "identity-service.yaml").write_text(
        "log_level: DEBUG\nhttp:\n  connect_timeout_seconds: 5\n",
        encoding="utf-8")
    issues = contract.validate_nacos_source(source)
    assert any("未声明 allow_override" in i and "connect_timeout_seconds" in i
               for i in issues)


def test_committed_nacos_source_valid():
    issues = contract.validate_nacos_source(FRAMEWORK_ROOT / "config" / "nacos")
    assert issues == []


# ---------------------------------------------------------------------------
# 集成:发布与漂移拒绝(真实 Nacos)
# ---------------------------------------------------------------------------

def test_publish_then_drift_refused(local_env, tmp_path, monkeypatch):
    pytest.importorskip("httpx")
    from config import publish as publish_module

    # 唯一 namespace:避免上轮运行篡改后的远端残留让"首次发布"误判漂移
    namespace = f"pubtest-{uuid.uuid4().hex[:8]}"
    server = local_env["NACOS_SERVER_ADDR"]
    source = tmp_path / "nacos"
    source.mkdir()
    (source / "application-shared.yaml").write_text(
        "log_level: INFO\n", encoding="utf-8")

    env_file = tmp_path / "environment.env"
    lines = []
    for raw in (FRAMEWORK_ROOT / ".local" / "environment.env").read_text(
            encoding="utf-8").splitlines():
        if raw.startswith("NACOS_NAMESPACE="):
            lines.append(f"NACOS_NAMESPACE={namespace}")
        else:
            lines.append(raw)
    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # 确保目标 namespace 存在(经 console 创建;已存在则忽略)
    console = local_env["NACOS_CONSOLE_ADDR"].rstrip("/")
    with httpx.Client(timeout=10) as client:
        resp = client.post(f"{console}/v3/auth/user/login", data={
            "username": local_env["NACOS_ADMIN_USER"],
            "password": local_env["NACOS_ADMIN_PASSWORD"]})
        token = resp.json()["accessToken"]
        client.post(f"{console}/v3/console/core/namespace",
                    headers={"accessToken": token},
                    data={"customNamespaceId": namespace,
                          "namespaceName": namespace, "namespaceDesc": "pubtest"})

    # 状态文件隔离:让 publish 状态落在 tmp_path
    monkeypatch.setattr(publish_module, "FRAMEWORK_ROOT", tmp_path)
    tmp_path.joinpath(".local").mkdir(exist_ok=True)
    # contract 校验依赖的真实源目录保持指向临时源
    args = type("A", (), {"env_file": str(env_file), "source": str(source),
                          "dry_run": False})()
    assert publish_module.publish(args) == 0, "首次发布应成功"

    args.dry_run = False
    assert publish_module.publish(args) == 0, "重复发布(未变更)应成功"

    # 直接经 admin API 篡改远端 → 下次发布必须拒绝覆盖(漂移)
    with httpx.Client(timeout=10) as client:
        resp = client.post(f"http://{server}/nacos/v1/auth/login", data={
            "username": local_env["NACOS_ADMIN_USER"],
            "password": local_env["NACOS_ADMIN_PASSWORD"]})
        token = resp.json()["accessToken"]
        tampered = client.post(
            f"http://{server}/nacos/v3/admin/cs/config",
            data={"accessToken": token, "dataId": "application-shared.yaml",
                  "groupName": "DEFAULT_GROUP", "namespaceId": namespace,
                  "content": "log_level: WARN  # 控制台漂移", "type": "yaml"})
        assert tampered.status_code == 200

    assert publish_module.publish(args) == 1, "漂移后发布必须拒绝(exit 1)"
