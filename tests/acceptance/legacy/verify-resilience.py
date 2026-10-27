"""本地混合示例的规则故障验收；只操作指定项目，结束时恢复原规则。"""

import argparse
import hashlib
import json
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import psycopg

ROOT = Path.cwd()
sys.path.insert(0, str(ROOT / "scripts/config"))
from contract import parse_env_file


def docker(*args):
    result = subprocess.run(
        ["docker", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=60,
    )
    if result.returncode:
        raise RuntimeError("本项目 Docker 验证命令失败；检查容器状态")
    return result.stdout


def until(check, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.25)
    raise AssertionError("等待验证条件超时")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--cookies-file", type=Path, required=True)
    parser.add_argument("--java-container", action="append", required=True)
    args = parser.parse_args()
    env = parse_env_file(args.env_file.resolve()).values
    project = env["PROJECT_CODE"]
    origin = env["PUBLIC_ORIGIN"]
    if env["APP_ENV"] != "local" or urlsplit(origin).hostname not in {
        "localhost",
        "127.0.0.1",
    }:
        raise ValueError("只允许本机隔离示例，不得用于正式环境")
    if any(
        not name.startswith(project + "-local-apps-") for name in args.java_container
    ):
        raise ValueError("容器不属于指定项目")
    runtime = args.env_file.resolve().parent / "runtime"
    java = dict(
        line.split("=", 1)
        for line in (runtime / "sample-java-service.env")
        .read_text(encoding="utf-8")
        .splitlines()
        if line and not line.startswith("#")
    )
    if not java["DB_NAME"].startswith(project.replace("-", "_") + "_local_"):
        raise ValueError("示例数据库归属不匹配")
    state = json.loads(args.cookies_file.read_text(encoding="utf-8"))
    cookies = {
        cookie["name"]: cookie["value"]
        for cookie in state["cookies"]
        if cookie["domain"] in {"localhost", "127.0.0.1"}
    }
    results = []

    def passed(name):
        results.append(name)
        print("PASS " + name, flush=True)

    with httpx.Client(trust_env=False, timeout=15) as nacos:
        login = nacos.post(
            env["NACOS_CONSOLE_ADDR"] + "/v3/auth/user/login",
            data={
                "username": env["NACOS_PUBLISH_USER"],
                "password": env["NACOS_PUBLISH_PASSWORD"],
            },
        )
        login.raise_for_status()
        params = {
            "accessToken": login.json()["accessToken"],
            "namespaceId": env["NACOS_NAMESPACE"],
            "groupName": "DEFAULT_GROUP",
            "dataId": "sample-java-service-sentinel-flow.json",
        }
        endpoint = "http://" + env["NACOS_SERVER_ADDR"] + "/nacos/v3/admin/cs/config"
        original = nacos.get(endpoint, params=params).json()["data"]["content"]
        modified = json.loads(original)
        for rule in modified:
            if rule["resource"] == "http.inbound":
                rule["count"] = 1
        valid = json.dumps(modified, ensure_ascii=False)

        def publish(content):
            response = nacos.post(
                endpoint, data={**params, "content": content, "type": "json"}
            )
            if response.status_code != 200 or response.json().get("code") != 0:
                raise RuntimeError("测试规则发布失败")

        def applied(content):
            digest = hashlib.sha256(content.encode()).hexdigest()

            # 非法候选未被数据源接受，重新发布原有效值不保证再次触发监听。
            # 检查每个节点最后接受的摘要，并由真实请求验证容量，不强求回调次数。
            def current(name):
                lines = [
                    line
                    for line in docker("logs", name).splitlines()
                    if "Sentinel rule accepted dataId=sample-java-service-sentinel-flow.json "
                    in line
                ]
                return bool(lines) and lines[-1].endswith("sha256=" + digest)

            until(lambda: all(current(name) for name in args.java_container))

        connection = {
            "host": env["PG_HOST"],
            "port": int(env["PG_PORT"]),
            "user": env["POSTGRES_ADMIN_USER"],
            "password": env["POSTGRES_ADMIN_PASSWORD"],
            "dbname": java["DB_NAME"],
        }

        def call():
            return httpx.get(
                origin + "/bff/web/api/v1/java-items",
                cookies=cookies,
                timeout=18,
                trust_env=False,
            )

        def prove_capacity(clear=False):
            # 在本项目独立示例库短暂锁表，制造真实活动请求；不增加业务故障接口。
            count = len(args.java_container) + (1 if clear else 0)
            with (
                psycopg.connect(**connection) as lock,
                psycopg.connect(**connection, autocommit=True) as monitor,
                ThreadPoolExecutor(max_workers=count + 1) as executor,
            ):
                lock.execute("SET lock_timeout='2s'")
                lock.execute("LOCK TABLE sample_item IN ACCESS EXCLUSIVE MODE")
                pending = []
                try:
                    for _ in range(count):
                        pending.append(executor.submit(call))
                    until(
                        lambda: (
                            monitor.execute(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=%s AND wait_event_type='Lock'",
                                (java["DB_USER"],),
                            ).fetchone()[0]
                            >= count
                        ),
                        seconds=6,
                    )
                    if not clear:
                        busy = call()
                        assert (
                            busy.status_code == 503
                            and busy.json()["code"] == "SERVICE_BUSY"
                        ), "并发阈值未按最后有效规则执行"
                    assert all(not future.done() for future in pending), (
                        "活动请求被提前终止"
                    )
                finally:
                    lock.commit()
                assert all(future.result().status_code == 200 for future in pending)

        def received(marker):
            # 等待官方数据源确实收到指定故障值，再通过真实请求验证旧规则仍在执行。
            # 异常由 Nacos 回调层记录，不能假定它出现在 Sentinel 的日志文件里。
            return {
                name: sum(
                    "New property value received" in line
                    and "sample-java-service-sentinel-flow.json" in line
                    and line.endswith(": " + marker)
                    for line in docker(
                        "exec",
                        name,
                        "sh",
                        "-c",
                        "cat /root/logs/csp/sentinel-record.log*",
                    ).splitlines()
                )
                for name in args.java_container
            }

        def invalid_seen(before, marker):
            until(
                lambda: all(
                    value > before[name] for name, value in received(marker).items()
                )
            )

        paused = False
        try:
            assert call().status_code == 200, (
                "需先完成业务登录、角色授权并保存浏览器会话"
            )
            publish(valid)
            applied(valid)
            prove_capacity()
            passed(
                "valid update converges on all listed Java nodes and enforces capacity"
            )
            before = received("{}")
            publish("{}")
            invalid_seen(before, "{}")
            prove_capacity()
            passed("invalid JSON retains last valid rules")

            image = docker(
                "inspect", "--format", "{{.Image}}", args.java_container[0]
            ).strip()
            probe_name = project + "-sentinel-startup-" + uuid.uuid4().hex[:10]
            command = [
                "docker",
                "run",
                "--rm",
                "--name",
                probe_name,
                "--network",
                project + "-local-net",
                "--env-file",
                str(runtime / "sample-java-service.env"),
                "-e",
                "JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 -Xmx256m",
                "-e",
                "SERVICE_ADVERTISE_HOST=sentinel-startup-rejection",
                "-v",
                str(runtime / "sample-java-service-secrets") + ":/run/secrets:ro",
                image,
            ]
            try:
                rejected = subprocess.run(
                    command, check=False, capture_output=True, timeout=60
                )
            finally:
                subprocess.run(
                    ["docker", "rm", "-f", probe_name],
                    check=False,
                    capture_output=True,
                    timeout=20,
                )
            output = (rejected.stdout + rejected.stderr).decode(
                "utf-8", errors="replace"
            )
            (ROOT / ".local/resilience-startup-rejection.log").write_text(
                output, encoding="utf-8"
            )
            assert (
                rejected.returncode != 0
                and "Sentinel rule initialization rejected; service will not register"
                in output
            )
            passed("fresh process rejects invalid initial rules")

            publish(valid)
            applied(valid)
            before = received("null")
            deleted = nacos.delete(endpoint, params=params)
            assert deleted.status_code == 200 and deleted.json().get("code") == 0
            invalid_seen(before, "null")
            prove_capacity()
            passed("deleted configuration retains last valid rules")
            publish(valid)
            applied(valid)

            docker("pause", project + "-local-infra-nacos-1")
            paused = True
            prove_capacity()
            passed(
                "brief Nacos disconnect retains rules while discovery cache is valid"
            )
            docker("unpause", project + "-local-infra-nacos-1")
            paused = False

            publish("[]")
            applied("[]")
            prove_capacity(clear=True)
            passed("explicit empty list clears admission rules")
        finally:
            if paused:
                docker("unpause", project + "-local-infra-nacos-1")
            publish(original)
            applied(original)
        passed("original rules restored")
        (ROOT / ".local/resilience-results.json").write_text(
            json.dumps({"nodes": args.java_container, "passed": results}, indent=2),
            encoding="utf-8",
        )


if __name__ == "__main__":
    try:
        main()
    except (
        AssertionError,
        RuntimeError,
        ValueError,
        OSError,
        KeyError,
        TypeError,
        httpx.HTTPError,
        psycopg.Error,
        subprocess.SubprocessError,
    ) as error:
        # 网络异常可能包含带 accessToken 的 URL，不直接打印异常内容。
        print(
            "FAIL " + type(error).__name__ + "; inspect local fixture and server state",
            file=sys.stderr,
        )
        raise SystemExit(1)
