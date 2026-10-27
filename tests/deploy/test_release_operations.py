import copy
import hashlib
import importlib.util
import json
import os
import socket
import ssl
import subprocess
import sys
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


ROOT = Path(__file__).parents[2]
verify = module("verify_release", ROOT / "tools/deploy/verify_release.py")
tls = module("validate_tls", ROOT / "tools/deploy/validate_tls.py")
up = module("infra_up_release", ROOT / "tools/infra/up.py")
sys.path.insert(0, str(ROOT / "tools/deploy"))
activate = module(
    "activate_certificate", ROOT / "tools/deploy/activate_certificate.py"
)
deployment = module("deployment_receipt", ROOT / "tools/deploy/deployment_receipt.py")


def release(root):
    compose = root / "deploy/compose.yaml"
    compose.parent.mkdir(parents=True)
    compose.write_text("services: {}", encoding="utf-8")
    return {
        "id": "sample-1.0.0",
        "project": "sample",
        "environment": "test",
        "compose_files": ["deploy/compose.yaml"],
        "images": {"identity-service": "registry.example/identity@sha256:" + "a" * 64},
        "files": {
            "deploy/compose.yaml": hashlib.sha256(compose.read_bytes()).hexdigest()
        },
        "migrations": [],
        "compatible_schema_from": [],
        "rollback_window_hours": 24,
        "compatible_previous_versions": [],
        "owner": "fixture-owner",
        "approval_reference": "fixture-confirmation",
    }


@pytest.mark.parametrize("value", [None, 0, -1, True, "24", 10**15])
def test_release_rejects_invalid_rollback_window(tmp_path, value):
    item = release(tmp_path)
    item["rollback_window_hours"] = value
    with pytest.raises(ValueError):
        verify.validate(tmp_path, item, "sample", "test")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("owner", ""),
        ("approval_reference", "replace-pending"),
        ("compatible_previous_versions", "previous"),
        ("compatible_previous_versions", ["../wrong"]),
    ],
)
def test_release_requires_explicit_rollback_responsibility(tmp_path, field, value):
    item = release(tmp_path)
    item[field] = value
    with pytest.raises(ValueError):
        verify.validate(tmp_path, item, "sample", "test")


def test_rollback_requires_current_project_window_and_bidirectional_compatibility(
    tmp_path,
):
    item = release(tmp_path)
    item["compatible_previous_versions"] = ["sample-0.9.0"]
    now = datetime(2026, 10, 27, 4, tzinfo=UTC)
    current = deployment.receipt(item, b"verified manifest", now)
    assert current["rollback_deadline"] == (now + timedelta(hours=24)).isoformat()
    assert current["contract_not_before"] == current["rollback_deadline"]
    target = item | {"id": "sample-0.9.0", "compatible_schema_from": [item["id"]]}
    deployment.check_rollback(target, current, item["id"], now + timedelta(hours=1))
    for when in (now - timedelta(seconds=1), now + timedelta(hours=24)):
        with pytest.raises(ValueError, match="窗口"):
            deployment.check_rollback(target, current, item["id"], when)
    for altered in (
        target | {"project": "foreign"},
        target | {"environment": "prod"},
        target | {"id": "sample-0.8.0"},
        target | {"compatible_schema_from": []},
        target | {"migrations": [{"target": "identity-db"}]},
    ):
        with pytest.raises(ValueError):
            deployment.check_rollback(altered, current, item["id"], now)
    with pytest.raises(ValueError):
        deployment.check_rollback(target, current, "stale-release", now)
    with pytest.raises(ValueError, match="UTC"):
        deployment.check_rollback(
            target, current | {"completed_at": "2026-10-27T04:00:00"}, item["id"], now
        )
    first = tmp_path / "operation-1-deployment.json"
    deployment.save(first, current)
    assert json.loads(first.read_text(encoding="utf-8")) == current
    latest = tmp_path / "latest-deployment.json"
    assert json.loads(latest.read_text(encoding="utf-8")) == current
    with pytest.raises(FileExistsError):
        deployment.save(first, current | {"release": "overwrite"})
    assert json.loads(latest.read_text(encoding="utf-8")) == current
    deployment.save(
        tmp_path / "operation-2-deployment.json", current | {"release": "next"}
    )
    assert json.loads(latest.read_text(encoding="utf-8"))["release"] == "next"
    assert json.loads(first.read_text(encoding="utf-8"))["release"] == item["id"]


def test_release_rejects_wrong_environment_changed_file_traversal_and_unhashed_migration(
    tmp_path,
):
    item = release(tmp_path)
    verify.validate(tmp_path, item, "sample", "test")
    with pytest.raises(TypeError):
        verify.validate(tmp_path, [], "sample", "test")
    with pytest.raises(TypeError):
        verify.validate(tmp_path, item | {"migrations": "wrong"}, "sample", "test")
    with pytest.raises(ValueError):
        verify.validate(tmp_path, item, "sample", "prod")
    altered = copy.deepcopy(item)
    altered["files"]["../foreign.sql"] = "a" * 64
    with pytest.raises(ValueError):
        verify.validate(tmp_path, altered, "sample", "test")
    path = tmp_path / "migrations"
    path.mkdir()
    (path / "V1__test.sql").write_text("SELECT 1;", encoding="utf-8")
    item["migrations"] = [
        {"target": "identity-db", "tool": "flyway", "path": "migrations"}
    ]
    with pytest.raises(ValueError, match="未登记"):
        verify.validate(tmp_path, item, "sample", "test")
    item["migrations"] = []
    (tmp_path / "deploy/compose.yaml").write_text("changed", encoding="utf-8")
    with pytest.raises(ValueError, match="摘要"):
        verify.validate(tmp_path, item, "sample", "test")


def test_compose_requires_matching_image_name_and_real_healthcheck(
    tmp_path, monkeypatch
):
    item = release(tmp_path)
    data = {
        "name": "sample-test-apps",
        "services": {
            "identity-service": {
                "image": item["images"]["identity-service"],
                "healthcheck": {"test": ["CMD", "curl", "localhost"]},
            }
        },
    }
    monkeypatch.setattr(
        verify.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0, stdout=json.dumps(data).encode()
        ),
    )
    verify.compose(tmp_path, item, tmp_path / "private.env", ["identity-service"])
    data["services"]["identity-service"]["image"] = "identity:latest"
    with pytest.raises(ValueError):
        verify.compose(tmp_path, item, tmp_path / "private.env", ["identity-service"])
    data["services"]["identity-service"]["image"] = item["images"]["identity-service"]
    data["services"]["identity-service"]["healthcheck"] = {"test": ["NONE"]}
    with pytest.raises(ValueError):
        verify.compose(tmp_path, item, tmp_path / "private.env", ["identity-service"])
    data["services"]["identity-service"]["healthcheck"] = {
        "test": ["CMD", "curl", "localhost"],
        "disable": True,
    }
    with pytest.raises(ValueError):
        verify.compose(tmp_path, item, tmp_path / "private.env", ["identity-service"])


def certificate(folder, host):
    folder.mkdir(parents=True)
    config = folder / "req.cnf"
    config.write_text("[req]\ndistinguished_name=dn\n[dn]\n", encoding="utf-8")
    result = subprocess.run(
        [
            "openssl",
            "req",
            "-config",
            str(config),
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "30",
            "-subj",
            f"/CN={host}",
            "-addext",
            f"subjectAltName=DNS:{host}",
            "-keyout",
            str(folder / "privkey.pem"),
            "-out",
            str(folder / "fullchain.pem"),
        ],
        capture_output=True,
        check=False,
        timeout=20,
    )
    assert result.returncode == 0


def test_real_openssl_accepts_pair_and_rejects_wrong_hostname_or_key(tmp_path):
    certificate(tmp_path / "current", "app.example.com")
    assert len(tls.validate(tmp_path, "app.example.com")) == 64
    with pytest.raises(ValueError):
        tls.validate(tmp_path, "other.example.com")
    certificate(tmp_path / "other", "app.example.com")
    (tmp_path / "current/privkey.pem").write_bytes(
        (tmp_path / "other/privkey.pem").read_bytes()
    )
    with pytest.raises(ValueError, match="不匹配"):
        tls.validate(tmp_path, "app.example.com")


def test_infra_only_selected_self_hosted_component_gets_its_secret(
    tmp_path, monkeypatch
):
    secret = tmp_path / "pg.password"
    secret.write_text("fixture-private-value", encoding="utf-8")
    values = {
        "APP_ENV": "test",
        "PG_MODE": "self_hosted",
        "REDIS_MODE": "external",
        "NACOS_MODE": "external",
        "POSTGRES_ADMIN_PASSWORD_SECRET_FILE": str(secret),
    }
    monkeypatch.setattr(
        up.contract, "parse_env_file", lambda path: SimpleNamespace(values=values)
    )
    monkeypatch.setattr(up.contract, "validate_env", lambda env: [])
    commands = []
    monkeypatch.setattr(
        up.subprocess,
        "run",
        lambda args, **kwargs: (
            commands.append((args, kwargs)) or SimpleNamespace(returncode=0)
        ),
    )
    monkeypatch.setattr(
        up.sys,
        "argv",
        ["up.py", "--env-file", str(tmp_path / "test.env"), "--service", "postgres"],
    )
    assert up.main() == 0
    args, kwargs = commands[0]
    assert args[-1] == "postgres"
    assert "fixture-private-value" not in " ".join(args)
    assert kwargs["env"]["POSTGRES_ADMIN_PASSWORD"] == "fixture-private-value"
    monkeypatch.setattr(
        up.sys,
        "argv",
        ["up.py", "--env-file", str(tmp_path / "test.env"), "--service", "redis"],
    )
    assert up.main() == 2
    assert len(commands) == 1


@pytest.mark.skipif(os.name != "posix", reason="Ansible 管理入口在 Linux 执行")
def test_ansible_rollback_checks_current_receipt_inside_lock(tmp_path):
    item = release(tmp_path)
    now = datetime.now(UTC)
    source = item | {"id": "sample-2.0.0", "compatible_previous_versions": [item["id"]]}
    item["compatible_schema_from"] = [source["id"]]
    manifest = tmp_path / "release.yaml"
    manifest.write_text(yaml.safe_dump(item), encoding="utf-8")
    state = tmp_path / "state/sample-test"
    state.mkdir(parents=True)
    current = deployment.receipt(source, b"source-manifest", now - timedelta(hours=25))
    latest = state / "latest-deployment.json"
    latest.write_text(json.dumps(current), encoding="utf-8")
    variables = {
        "ansible_connection": "local",
        "ansible_python_interpreter": "/usr/local/bin/python",
        "tools_python": "/usr/local/bin/python",
        "project_source": str(ROOT),
        "release_file": str(manifest),
        "project_code": "sample",
        "app_env": "test",
        "control_state_dir": str(tmp_path / "state"),
        "operation_id": "expired-rollback",
        "rollback_requested": True,
        "rollback_authorization_reviewed": True,
        "rollback_from": source["id"],
    }
    inventory = tmp_path / "inventory.yaml"
    inventory.write_text(
        yaml.safe_dump(
            {
                "all": {
                    "vars": variables,
                    "children": {
                        "release_controller": {"hosts": {"control": {}}},
                        "apps": {
                            "hosts": {"app01": {"app_services": ["identity-service"]}}
                        },
                    },
                }
            }
        ),
        encoding="utf-8",
    )
    # 使用实际 deploy.yaml 的锁内前置检查；不执行真实应用更新。
    controller = yaml.safe_load((ROOT / "tools/deploy/ansible/deploy.yaml").read_text())[1]
    play = tmp_path / "rollback-check.yaml"
    play.write_text(
        yaml.safe_dump(
            [
                {"import_playbook": str(ROOT / "tools/deploy/ansible/controller-start.yaml")},
                controller,
                {
                    "import_playbook": str(
                        ROOT / "tools/deploy/ansible/controller-finish.yaml"
                    )
                },
            ]
        ),
        encoding="utf-8",
    )
    command = ["ansible-playbook", "-i", str(inventory), str(play)]
    failed = subprocess.run(command, capture_output=True, check=False, timeout=45)
    assert failed.returncode != 0
    assert (state / "lock/owner.json").is_file()
    assert not (state / "expired-rollback.json").exists()
    assert json.loads(latest.read_text()) == current
    # 测试夹具明确核对旧任务退出后移走其锁；真实入口不会自动解锁。
    (state / "lock").rename(state / "failed-lock-evidence")
    current = deployment.receipt(source, b"source-manifest", now)
    latest.write_text(json.dumps(current), encoding="utf-8")
    passed = subprocess.run(
        command + ["-e", "operation_id=valid-rollback"],
        capture_output=True,
        check=False,
        timeout=45,
    )
    assert passed.returncode == 0, passed.stdout.decode(errors="replace")
    assert not (state / "lock").exists()
    assert (state / "valid-rollback.json").is_file()


@pytest.mark.skipif(os.name != "posix", reason="Ansible 管理入口在 Linux 执行")
def test_ansible_failure_keeps_lock_and_stops_next_host(tmp_path):
    inventory = tmp_path / "hosts.yaml"
    inventory.write_text(
        "all:\n  vars:\n    ansible_connection: local\n"
        "    ansible_python_interpreter: /usr/local/bin/python\n"
        "    project_code: lock-fixture\n    app_env: test\n"
        "    operation_id: lock-test\n"
        f"    control_state_dir: {tmp_path / 'state'}\n"
        "  children:\n    release_controller:\n      hosts:\n        control: {}\n"
        "    apps:\n      hosts:\n        first: {}\n        second: {}\n",
        encoding="utf-8",
    )
    play = tmp_path / "verify.yaml"
    start = ROOT / "tools/deploy/ansible/controller-start.yaml"
    finish = ROOT / "tools/deploy/ansible/controller-finish.yaml"
    play.write_text(
        f"- import_playbook: {start}\n"
        "- hosts: apps\n  gather_facts: false\n  serial: 1\n"
        "  any_errors_fatal: true\n  tasks:\n"
        "    - ansible.builtin.fail:\n        msg: simulated update failed\n"
        "      when: inventory_hostname == 'first'\n"
        f"    - ansible.builtin.copy:\n        dest: {tmp_path / 'second-was-updated'}\n"
        "        content: unexpected\n"
        f"- import_playbook: {finish}\n",
        encoding="utf-8",
    )
    command = ["ansible-playbook", "-i", str(inventory), str(play)]
    failed = subprocess.run(command, check=False, capture_output=True, timeout=45)
    assert failed.returncode != 0
    state = tmp_path / "state/lock-fixture-test"
    assert (state / "lock/owner.json").is_file()
    assert not (state / "lock-test.json").exists()
    assert not (tmp_path / "second-was-updated").exists()
    locked = subprocess.run(command, check=False, capture_output=True, timeout=45)
    assert locked.returncode != 0
    assert b"simulated update failed" not in locked.stdout
    # 另一个项目不受此锁影响；完整成功后释放它自己的锁。
    play.write_text(
        f"- import_playbook: {start}\n- import_playbook: {finish}\n", encoding="utf-8"
    )
    success = subprocess.run(
        command + ["-e", "project_code=lock-other"],
        check=False,
        capture_output=True,
        timeout=45,
    )
    assert success.returncode == 0, success.stdout.decode(errors="replace")
    assert (tmp_path / "state/lock-other-test/lock-test.json").is_file()
    assert not (tmp_path / "state/lock-other-test/lock").exists()
    assert (state / "lock/owner.json").is_file()
    replay = subprocess.run(
        command + ["-e", "project_code=lock-other"],
        capture_output=True,
        check=False,
        timeout=45,
    )
    assert replay.returncode != 0
    assert not (tmp_path / "state/lock-other-test/lock").exists()


@pytest.mark.skipif(os.name != "posix", reason="证书原子引用在 Linux 执行")
def test_certificate_switch_restores_previous_reference_if_reload_fails(tmp_path):
    certificate(tmp_path / "first", "app.example.com")
    certificate(tmp_path / "second", "app.example.com")
    root = tmp_path / "tls"
    first = activate.activate(
        root,
        tmp_path / "first/fullchain.pem",
        tmp_path / "first/privkey.pem",
        "app.example.com",
        lambda: None,
    )
    calls = []

    def failed_once():
        calls.append(1)
        if len(calls) == 1:
            raise ValueError("reload failed")

    with pytest.raises(ValueError):
        activate.activate(
            root,
            tmp_path / "second/fullchain.pem",
            tmp_path / "second/privkey.pem",
            "app.example.com",
            failed_once,
        )
    assert len(calls) == 2
    assert tls.validate(root, "app.example.com") == first
    assert (
        activate.activate(
            root,
            tmp_path / "first/fullchain.pem",
            tmp_path / "first/privkey.pem",
            "app.example.com",
            lambda: None,
        )
        == first
    )
    current = os.readlink(root / "current")
    (root / "current/fullchain.pem").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="内容不一致"):
        activate.activate(
            root,
            tmp_path / "first/fullchain.pem",
            tmp_path / "first/privkey.pem",
            "app.example.com",
            lambda: None,
        )
    assert os.readlink(root / "current") == current


@pytest.mark.skipif(
    os.getenv("PLATFORM_TEST_DOCKER_TLS") != "1", reason="需显式本地 Docker TLS 验证"
)
def test_real_nginx_tls_uses_validated_certificate_and_rejects_internal_route(tmp_path):
    certificate(tmp_path / "certificates/current", "app.example.com")
    renderer = "from pathlib import Path;from jinja2 import Template;Path('/fixture/default.conf').write_text(Template(Path('/template/deploy/nginx/server.conf.j2').read_text()).render(edge_server_name='app.example.com',edge_gateway_upstreams=['127.0.0.1:18080']))"
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--volume",
            f"{ROOT.resolve()}:/framework:ro",
            "--volume",
            f"{tmp_path.resolve()}:/fixture",
            "local/ken-ansible-check:2.19.3",
            "python",
            "-c",
            renderer,
        ],
        check=True,
        capture_output=True,
        timeout=30,
    )
    name = "slim-tls-" + uuid.uuid4().hex[:12]
    image = os.environ["PLATFORM_TEST_NGINX_IMAGE"]
    try:
        subprocess.run(
            [
                "docker",
                "run",
                "-d",
                "--name",
                name,
                "-p",
                "127.0.0.1::8443",
                "--volume",
                f"{tmp_path.resolve()}/default.conf:/etc/nginx/conf.d/default.conf:ro",
                "--volume",
                f"{tmp_path.resolve()}/certificates:/etc/nginx/tls:ro",
                image,
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )
        info = json.loads(
            subprocess.run(
                ["docker", "inspect", name], check=True, capture_output=True, timeout=10
            ).stdout
        )[0]
        port = int(info["NetworkSettings"]["Ports"]["8443/tcp"][0]["HostPort"])
        context = ssl.create_default_context(
            cafile=str(tmp_path / "certificates/current/fullchain.pem")
        )
        deadline = time.monotonic() + 10
        while True:
            try:
                with (
                    socket.create_connection(("127.0.0.1", port), timeout=2) as raw,
                    context.wrap_socket(raw, server_hostname="app.example.com"),
                ):
                    break
            except (ConnectionError, ssl.SSLEOFError, TimeoutError):
                if time.monotonic() >= deadline:
                    logs = subprocess.run(
                        ["docker", "logs", name],
                        check=False,
                        capture_output=True,
                        timeout=5,
                    )
                    raise AssertionError(logs.stderr.decode(errors="replace")) from None
                time.sleep(0.1)
        for path, expected in [
            ("/", b"200 OK"),
            ("/actuator/health", b"404 Not Found"),
            # 夹具未启动 Gateway；502 证明协议入口进入代理而非 SPA fallback。
            ("/userinfo", b"502 Bad Gateway"),
        ]:
            with (
                socket.create_connection(("127.0.0.1", port), timeout=10) as raw,
                context.wrap_socket(raw, server_hostname="app.example.com") as secure,
            ):
                secure.sendall(
                    f"GET {path} HTTP/1.1\r\nHost: app.example.com\r\nConnection: close\r\n\r\n".encode()
                )
                assert expected in secure.recv(4096).split(b"\r\n", 1)[0]
        with (
            socket.create_connection(("127.0.0.1", port), timeout=10) as raw,
            pytest.raises(ssl.SSLCertVerificationError),
        ):
            context.wrap_socket(raw, server_hostname="other.example.com")
    finally:
        subprocess.run(
            ["docker", "rm", "-f", name], check=False, capture_output=True, timeout=15
        )


def test_partial_failure_recovers_recorded_scope_without_faking_success(tmp_path):
    old = release(tmp_path)
    old["compatible_schema_from"] = ["sample-2.0.0"]
    now = datetime.now(UTC)
    targets = {"first": ["identity-service"]}
    baseline = deployment.receipt(old, b"old", now, targets)
    baseline["deployments"] = {
        "first": {
            "identity-service": {
                "image": old["images"]["identity-service"],
                "release": old["id"],
            }
        }
    }
    deployment.save(tmp_path / "old-deployment.json", baseline)
    new = old | {
        "id": "sample-2.0.0",
        "images": {"identity-service": "registry.example/new@sha256:" + "b" * 64},
        "compatible_previous_versions": [old["id"]],
    }
    deployment.begin(tmp_path / "failed-attempt.json", new, b"new", targets, now)
    attempt = json.loads((tmp_path / "latest-attempt.json").read_text())
    assert json.loads((tmp_path / "latest-deployment.json").read_text()) == baseline
    deployment.check_rollback(old, baseline, new["id"], now, attempt, targets)
    with pytest.raises(ValueError, match="全部实例"):
        deployment.check_rollback(
            old, baseline, new["id"], now, attempt, {"other": ["identity-service"]}
        )
    with pytest.raises(ValueError, match="窗口"):
        deployment.check_rollback(
            old, baseline, new["id"], now + timedelta(hours=25), attempt, targets
        )
    with pytest.raises(ValueError, match="未完成"):
        deployment.begin(tmp_path / "bypass-attempt.json", new, b"new", targets, now)
    deployment.begin(
        tmp_path / "recover-attempt.json",
        old,
        b"old",
        targets,
        now,
        "failed",
        new["id"],
    )
    recovering = json.loads((tmp_path / "latest-attempt.json").read_text())
    assert recovering["schema_release"] == new["id"]
    assert recovering["recovery_deadline"] == attempt["recovery_deadline"]
    deployment.check_rollback(old, baseline, new["id"], now, recovering, targets)
    extra = attempt | {"targets": {"second": ["identity-service"]}}
    with pytest.raises(ValueError, match="新增服务"):
        deployment.check_rollback(
            old, baseline, new["id"], now, extra, extra["targets"]
        )


def test_selected_receipt_does_not_claim_unselected_images(tmp_path):
    item = release(tmp_path)
    item["images"]["nginx"] = "nginx@sha256:" + "c" * 64
    value = deployment.receipt(
        item, b"input", datetime.now(UTC), {"first": ["identity-service"]}
    )
    assert set(value["images"]) == {"identity-service"}


@pytest.mark.skipif(os.name != "posix", reason="Ansible 管理入口在 Linux 执行")
def test_first_install_waits_for_all_hosts_before_public_smoke(tmp_path):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread

    ready = tmp_path / "edge-ready"
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            status = 200 if ready.exists() else 503
            calls.append(status)
            self.send_response(status)
            self.end_headers()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        inventory = tmp_path / "inventory.yaml"
        inventory.write_text(
            yaml.safe_dump(
                {
                    "all": {
                        "vars": {
                            "ansible_connection": "local",
                            "ansible_python_interpreter": "/usr/local/bin/python",
                            "smoke_url": f"http://127.0.0.1:{server.server_port}",
                        },
                        "children": {
                            "apps": {"hosts": {"app01": {}, "app02": {}}},
                            "release_controller": {"hosts": {"control": {}}},
                        },
                    }
                }
            )
        )
        source = yaml.safe_load((ROOT / "tools/deploy/ansible/deploy.yaml").read_text())
        app = next(
            p for p in source if p.get("hosts") == "apps" and p.get("serial") == 1
        )
        # 应用更新由标记夹具代替，保留真实 play 的调度属性和原有所有 URI 烟测位置。
        app = {k: v for k, v in app.items() if k != "tasks"} | {
            "tasks": [
                {
                    "ansible.builtin.copy": {"dest": str(ready), "content": "ready"},
                    "when": "inventory_hostname == 'app02'",
                }
            ]
            + [
                t
                for p in source
                if p.get("hosts") == "apps"
                for t in p.get("tasks", [])
                if "ansible.builtin.uri" in t
            ]
        }
        final = next(
            p
            for p in source
            if any("ansible.builtin.uri" in t for t in p.get("tasks", []))
            and p.get("hosts") == "release_controller"
        )
        final = {k: v for k, v in final.items() if k != "tasks"} | {
            "tasks": [t for t in final["tasks"] if "ansible.builtin.uri" in t]
        }
        play = tmp_path / "first.yaml"
        play.write_text(yaml.safe_dump([app, final]))
        result = subprocess.run(
            ["ansible-playbook", "-i", str(inventory), str(play)],
            capture_output=True,
            check=False,
            timeout=45,
        )
        assert result.returncode == 0, result.stdout.decode(errors="replace")
        assert calls == [200, 200]
    finally:
        server.shutdown()
        thread.join()


@pytest.mark.skipif(os.name != "posix", reason="Ansible 管理入口在 Linux 执行")
def test_nginx_failed_smoke_keeps_backup_for_explicit_manual_restore(tmp_path):
    edge = tmp_path / ".local/edge"
    edge.mkdir(parents=True)
    operations = tmp_path / ".local/operations"
    operations.mkdir()
    previous = "server { listen 80; return 200; }\n"
    candidate = "server { listen 80; return 502; }\n"
    (edge / "default.conf").write_text(previous)
    (edge / "candidate.conf").write_text(candidate)
    # 执行真实发布任务中的备份和开始凭据；模拟后续烟测失败。
    # nginx -t / TLS 的真实容器验证由独立用例覆盖，本用例验证人工恢复材料。
    edge_tasks = yaml.safe_load((ROOT / "tools/deploy/ansible/tasks/edge.yaml").read_text())
    update_tasks = yaml.safe_load(
        (ROOT / "tools/deploy/ansible/tasks/update-service.yaml").read_text()
    )
    backup = next(t for t in edge_tasks if t.get("register") == "edge_config_backup")
    record = next(
        t
        for t in update_tasks
        if t.get("name") == "停止实例前记录可恢复证据，不将已开始视为成功"
    )
    variables = {
        "project_root": str(tmp_path),
        "edge_config_file": str(edge / "default.conf"),
        "operation_id": "failed-nginx",
        "application_service": "nginx",
        "previous_service_images": {"stdout_lines": ["nginx@sha256:old"]},
        "release": {"images": {"nginx": "nginx@sha256:new"}},
        "ansible_python_interpreter": sys.executable,
    }
    play = tmp_path / "edge.yaml"
    play.write_text(
        yaml.safe_dump(
            [
                {
                    "hosts": "localhost",
                    "connection": "local",
                    "gather_facts": False,
                    "vars": variables,
                    "tasks": [
                        backup,
                        record,
                        {"ansible.builtin.fail": {"msg": "smoke failed"}},
                    ],
                }
            ]
        )
    )
    result = subprocess.run(
        ["ansible-playbook", "-i", "localhost,", str(play)],
        capture_output=True,
        check=False,
        timeout=45,
    )
    assert result.returncode != 0
    evidence = json.loads((operations / "failed-nginx-nginx.json").read_text())
    assert evidence["status"] == "started"
    assert evidence["previous_images"] == ["nginx@sha256:old"]
    assert Path(evidence["previous_config"]).read_text() == previous
    assert (edge / "default.conf").read_text() == candidate
    assert not (operations / "failed-nginx-nginx-ready.json").exists()
    # 运维显式恢复记录指向的旧文件；此动作不写全批次成功凭据。
    (edge / "default.conf").write_bytes(Path(evidence["previous_config"]).read_bytes())
    assert (edge / "default.conf").read_text() == previous
    assert json.loads((operations / "failed-nginx-nginx.json").read_text()) == evidence
