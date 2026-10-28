import importlib.util
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import pytest
import yaml

from scripts.config.contract import EnvFile, validate_env
from scripts.prepare_local import metrics_binding, monitoring_inputs, sync_local_monitoring_proxy

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("infra_up_monitoring", ROOT / "scripts/infra/up.py")
up = importlib.util.module_from_spec(spec)
spec.loader.exec_module(up)


def test_local_proxy_sync_is_optional_idempotent_and_tracks_only_current_network(
    tmp_path, monkeypatch
):
    env = tmp_path / "environment.env"
    env.write_text("PROJECT_CODE=fixture\nAPP_ENV=local\nSECRET=preserved\n", encoding="utf-8")
    calls = []
    address = ["172.22.0.5"]

    def output(command, **kwargs):
        calls.append(command)
        if command[1] == "inspect":
            return json.dumps(
                {
                    "fixture-local-net": {"IPAddress": address[0]},
                    "other": {"IPAddress": "172.23.0.9"},
                }
            )
        return "gateway-fixture\n"

    monkeypatch.setattr("scripts.prepare_local.subprocess.check_output", output)
    assert not sync_local_monitoring_proxy(tmp_path, env)
    assert calls == []
    env.write_text(env.read_text() + "COMPOSE_PROFILES=monitoring\nMONITORING_PROXY_CIDRS = old\n")
    with pytest.raises(ValueError, match="默认初始化"):
        sync_local_monitoring_proxy(tmp_path, env)
    runtime = tmp_path / ".local/runtime"
    runtime.mkdir(parents=True)
    (runtime / "compose.env").write_text("fixture")
    assert sync_local_monitoring_proxy(tmp_path, env)
    assert "MONITORING_PROXY_CIDRS=172.22.0.5/32" in env.read_text()
    assert env.read_text().count("MONITORING_PROXY_CIDRS") == 1
    assert not sync_local_monitoring_proxy(tmp_path, env)
    address[0] = "172.22.0.6"
    assert sync_local_monitoring_proxy(tmp_path, env)
    assert "SECRET=preserved" in env.read_text()
    assert "MONITORING_PROXY_CIDRS=172.22.0.6/32" in env.read_text()
    address[0] = "8.8.8.8"
    before = env.read_bytes()
    with pytest.raises(ValueError):
        sync_local_monitoring_proxy(tmp_path, env)
    assert env.read_bytes() == before


def test_java_healthchecks_use_main_port_when_management_port_changes():
    base = yaml.safe_load((ROOT / "deploy/compose.yaml").read_text(encoding="utf-8"))
    for name, port in (("identity-service", 8081), ("api-gateway", 8080)):
        probe = " ".join(base["services"][name]["healthcheck"]["test"])
        assert f"{port}/readyz" in probe
        assert "MANAGEMENT_SERVER_PORT" not in probe
    generated = yaml.safe_load(
        (ROOT / "deploy/generated.services.yaml").read_text(encoding="utf-8")
    )
    probe = " ".join(generated["services"]["sample-java-service"]["healthcheck"]["test"])
    assert "SERVICE_BIND_PORT" in probe and "/readyz" in probe


def test_disabled_monitoring_has_no_targets_or_bindings(tmp_path):
    path = monitoring_inputs(tmp_path, {"services": {}}, {}, "host.docker.internal")
    assert json.loads(path.read_text()) == []
    assert metrics_binding("java", 8080, False) == {}
    assert metrics_binding("python", 8080, False) == {}
    assert "grafana" not in up.selected_services({"APP_ENV": "local"})


def test_selected_service_targets_and_actual_bindings(tmp_path):
    config = {"services": {"inspection": {"port": 48083, "language": "python"}}}
    path = monitoring_inputs(
        tmp_path, config, {"COMPOSE_PROFILES": "monitoring"}, "host.docker.internal"
    )
    rows = json.loads(path.read_text())
    assert [r["labels"]["service"] for r in rows] == [
        "identity-service",
        "api-gateway",
        "inspection",
    ]
    assert rows[-1]["targets"] == ["host.docker.internal:49083"]
    assert metrics_binding("python", 48083, True)["METRICS_PORT"] == 49083
    assert metrics_binding("java", 8080, True)["MANAGEMENT_SERVER_PORT"] == 9080
    with pytest.raises(ValueError):
        metrics_binding("java", 65000, True)


def test_monitoring_rejects_broad_proxy_trust_and_unpinned_images():
    issues = validate_env(
        EnvFile(
            {
                "APP_ENV": "local",
                "COMPOSE_PROFILES": "monitoring",
                "MONITORING_PROXY_CIDRS": "172.20.0.0/16",
            }
        )
    )
    assert any("PROMETHEUS_IMAGE" in issue for issue in issues)
    assert any("可信网关" in issue for issue in issues)


def test_inventory_uses_only_selected_private_service_endpoints():
    hosts = {
        "app": {
            "ansible_host": "10.20.0.1",
            "app_services": ["inspection", "nginx"],
            "metrics_ports": {"inspection": 49083},
        }
    }
    inventory = {"all": {"children": {"apps": {"hosts": hosts}}}}
    assert up.deployment_targets({"inspection": {"language": "python"}}, inventory) == [
        {
            "targets": ["10.20.0.1:49083"],
            "labels": {"service": "inspection", "__metrics_path__": "/metrics"},
        }
    ]
    hosts["app"]["ansible_host"] = "8.8.8.8"
    with pytest.raises(ValueError):
        up.deployment_targets({"inspection": {"language": "python"}}, inventory)


def test_compose_monitoring_is_optional_and_grafana_not_anonymous():
    data = yaml.safe_load((ROOT / "infra/compose.yaml").read_text(encoding="utf-8"))
    for service in ("prometheus", "grafana"):
        assert data["services"][service]["profiles"] == ["monitoring"]
    assert "ports" not in data["services"]["prometheus"]
    ini = (ROOT / "infra/monitoring/grafana.ini").read_text()
    assert "[auth.anonymous]\nenabled = false" in ini
    assert "enable_login_token = false" in ini
    assert "max_connections = 0" in ini
    dashboard = json.loads((ROOT / "infra/monitoring/services.json").read_text(encoding="utf-8"))
    assert all("or vector(0)" not in p["targets"][0]["expr"] for p in dashboard["panels"])


@pytest.mark.integration
@pytest.mark.skipif(
    not os.getenv("PLATFORM_TEST_GRAFANA_IMAGE"), reason="需显式提供本地 Grafana 测试镜像"
)
def test_real_grafana_preserves_csp_directives_and_fresh_html_nonce():
    """用 Grafana 自身的 INI 解析和真实 HTML 检查分号及随机 nonce。"""
    name = "qa-grafana-csp-" + uuid.uuid4().hex
    container = None
    try:
        started = subprocess.run(
            [
                "docker", "run", "-d", "--name", name, "--label", f"qa.owner={name}",
                "-p", "127.0.0.1::3000", "--mount",
                f"type=bind,src={ROOT / 'infra/monitoring/grafana.ini'},dst=/etc/grafana/grafana.ini,readonly",
                "-e", "GF_SERVER_ROOT_URL=http://localhost/bff/identity/platform/monitoring/",
                "-e", "GF_SECURITY_ADMIN_PASSWORD=" + uuid.uuid4().hex,
                os.environ["PLATFORM_TEST_GRAFANA_IMAGE"],
            ],
            capture_output=True, check=True, timeout=30,
        )
        container = started.stdout.decode().strip()
        state = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
        port = state["NetworkSettings"]["Ports"]["3000/tcp"][0]["HostPort"]
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}/bff/identity/platform/monitoring/",
            headers={"X-WEBAUTH-USER": name, "X-WEBAUTH-ROLE": "Viewer"},
        )
        deadline = time.monotonic() + 90
        while True:
            try:
                response = opener.open(request, timeout=3)
                break
            except (OSError, urllib.error.URLError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        with response:
            policy = response.headers["Content-Security-Policy"]
            html = response.read().decode()
        assert "frame-ancestors 'self'" in policy
        assert "object-src 'none'" in policy
        assert "style-src 'self' 'unsafe-inline'" in policy
        nonce = re.search(r"script-src 'self' 'nonce-([^']+)'", policy).group(1)
        assert f'nonce="{nonce}"' in html
        with opener.open(request, timeout=5) as second:
            other = second.headers["Content-Security-Policy"]
            assert nonce not in other
            assert re.search(r"script-src 'self' 'nonce-([^']+)'", other)
    finally:
        if container:
            state = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
            assert state["Config"]["Labels"]["qa.owner"] == name
            subprocess.run(
                ["docker", "rm", "-f", container], check=True, capture_output=True, timeout=20
            )


def test_release_rejects_profile_drift_and_missing_exporters():
    from scripts.deploy.verify_release import monitoring_bindings

    config = {
        "services": {
            "api-gateway": {"environment": {"COMPOSE_PROFILES": "", "SERVICE_BIND_PORT": "8080"}}
        }
    }
    with pytest.raises(ValueError, match="不一致"):
        monitoring_bindings(config, ["api-gateway"], {"COMPOSE_PROFILES": "monitoring"})
    values = config["services"]["api-gateway"]["environment"]
    values["COMPOSE_PROFILES"] = "monitoring"
    with pytest.raises(ValueError, match="独立采集"):
        monitoring_bindings(config, ["api-gateway"], {"COMPOSE_PROFILES": "monitoring"})
    values.update(
        MANAGEMENT_SERVER_PORT="9080", MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,prometheus"
    )
    monitoring_bindings(config, ["api-gateway"], {"COMPOSE_PROFILES": "monitoring"})
