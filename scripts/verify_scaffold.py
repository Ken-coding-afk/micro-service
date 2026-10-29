"""从固定提交验证独立脚手架；只停止本次拥有的进程和Compose项目，保留数据。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

from common import FRAMEWORK, write
from registration import validate_name

PHASES = ("preflight", "generate", "dependencies", "initialize", "services", "smoke", "cleanup")


def stop_process(process: subprocess.Popen) -> None:
    if os.name == "nt":
        if process.poll() is None:
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
            )
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        # 父进程退出不保证子进程已退出，按本次独立进程组收尾。
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait(timeout=30)


class Verification:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.root = args.output.resolve()
        self.deadline = time.monotonic() + 3600
        self.children: list[subprocess.Popen] = []
        self.owns_environment = False
        self.current_phase = "preflight"
        self.report = {
            "schema_version": 1,
            "run_id": str(uuid.uuid4()),
            "status": "failed",
            "source_commit": None,
            "parameters": {
                "project": args.project,
                "port_offset": args.port_offset,
                "dev_host": args.dev_host,
            },
            "tools": {},
            "phases": {name: {"status": "not_run"} for name in PHASES},
            "locks": {},
            "remaining_resources": [],
        }

    def command(self, args: list, cwd: Path, timeout: int = 1200) -> None:
        budget = min(timeout, self.deadline - time.monotonic())
        if budget <= 0:
            raise TimeoutError("验收总预算耗尽")
        env = os.environ.copy()
        env.pop("UV_PROJECT_ENVIRONMENT", None)
        env["PYTHONIOENCODING"] = "utf-8"
        step = {"argv": [str(arg) for arg in args], "started_at": time.time()}
        self.report["phases"][self.current_phase].setdefault("commands", []).append(step)
        process = subprocess.Popen(
            [str(arg) for arg in args],
            cwd=cwd,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=os.name != "nt",
        )
        try:
            code = process.wait(timeout=budget)
            step["exit_code"] = code
            if code:
                raise subprocess.CalledProcessError(code, str(args[0]))
        finally:
            stop_process(process)
            step["ended_at"] = time.time()

    def phase(self, name: str, action) -> None:
        self.current_phase = name
        record = self.report["phases"][name]
        record.update(status="running", started_at=time.time())
        print("验收阶段：" + name, flush=True)
        try:
            action()
            record.update(status="passed", exit_code=0)
        except BaseException as error:
            record.update(
                status="failed",
                exit_code=getattr(error, "returncode", 1),
                error_type=type(error).__name__,
            )
            if isinstance(error, ValueError):
                record["reason"] = str(error)
            raise
        finally:
            record["ended_at"] = time.time()

    def docker_ids(self, kind: str, project: str) -> list[str]:
        command = ["docker", kind, "ls", "--filter", f"label=com.docker.compose.project={project}"]
        if kind == "container":
            command.append("--all")
        return subprocess.check_output(
            [*command, "--quiet"], text=True, timeout=30, stderr=subprocess.DEVNULL
        ).split()

    def preflight(self) -> None:
        validate_name(self.args.project)
        if self.root.exists() or not self.root.parent.is_dir():
            raise ValueError("候选目录必须不存在，父目录必须存在")
        if not 0 <= self.args.port_offset <= 10000:
            raise ValueError("端口偏移不合法")
        if not self.args.dev_host or any(c.isspace() for c in self.args.dev_host):
            raise ValueError("必须提供容器可达宿主地址")
        for tool, arguments in {
            "git": ["--version"],
            "uv": ["--version"],
            "java": ["-version"],
            "node": ["--version"],
            "pnpm": ["--version"],
            "docker": ["info", "--format", "{{.ServerVersion}}"],
        }.items():
            executable = shutil.which(tool)
            if not executable:
                raise ValueError("缺少工具：" + tool)
            self.report["tools"][tool] = subprocess.check_output(
                [executable, *arguments], stderr=subprocess.STDOUT, text=True, timeout=30
            ).strip()
        self.report["source_commit"] = subprocess.check_output(
            ["git", "rev-parse", "--verify", "--end-of-options", self.args.ref + "^{commit}"],
            cwd=FRAMEWORK,
            text=True,
            timeout=30,
            stderr=subprocess.DEVNULL,
        ).strip()
        for suffix in ("apps", "infra"):
            project = f"{self.args.project}-local-{suffix}"
            for kind in ("container", "volume", "network"):
                if self.docker_ids(kind, project):
                    raise ValueError("候选Compose项目已有资源")
        networks = subprocess.check_output(
            ["docker", "network", "ls", "--format", "{{.Name}}"], text=True, timeout=30
        ).split()
        if self.args.project + "-local-net" in networks:
            raise ValueError("候选网络已存在")
        ports = (18080, 18081, 15432, 16379, 8848, 9848, 8080, 48082, 48083)
        for base in ports:
            with socket.socket() as listener:
                listener.bind(("0.0.0.0", base + self.args.port_offset))

    def generate(self) -> None:
        self.command(
            [
                sys.executable,
                FRAMEWORK / "scripts/create.py",
                "--ref",
                self.report["source_commit"],
                "--output",
                self.root,
                "--project",
                self.args.project,
                "--port-offset",
                self.args.port_offset,
            ],
            FRAMEWORK,
        )

    def dependencies(self) -> None:
        self.command(["uv", "sync", "--frozen"], self.root)
        self.command(["uv", "lock", "--project", "services/inspection-service"], self.root)
        self.command(
            ["uv", "sync", "--frozen", "--project", "services/inspection-service"], self.root
        )
        pnpm = shutil.which("pnpm")
        self.command(
            [pnpm, "install", "--frozen-lockfile"], self.root / "services/identity-service/admin"
        )
        self.command(
            [pnpm, "exec", "playwright", "install", "chromium"],
            self.root / "services/identity-service/admin",
        )
        wrapper = self.root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        self.command([wrapper, "-pl", ":equipment-service", "-am", "spotless:apply"], self.root)
        for path in (
            self.root / "uv.lock",
            self.root / "sdk/python/uv.lock",
            self.root / "services/inspection-service/uv.lock",
            self.root / "services/identity-service/admin/pnpm-lock.yaml",
        ):
            self.report["locks"][path.relative_to(self.root).as_posix()] = hashlib.sha256(
                path.read_bytes()
            ).hexdigest()

    def initialize(self) -> None:
        self.owns_environment = True
        self.command(
            [
                "uv",
                "run",
                "--frozen",
                "python",
                "scripts/init.py",
                "--env",
                "local",
                "--services",
                "all",
                "--with-demo",
                "--dev-host",
                self.args.dev_host,
            ],
            self.root,
            2400,
        )

    def services(self) -> None:
        env = os.environ.copy()
        env.pop("UV_PROJECT_ENVIRONMENT", None)
        for service in ("equipment-service", "inspection-service"):
            self.children.append(
                subprocess.Popen(
                    ["uv", "run", "--frozen", "python", "scripts/dev.py", service],
                    cwd=self.root,
                    env=env,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=os.name != "nt",
                )
            )
        deadline = min(self.deadline, time.monotonic() + 600)
        local_http = build_opener(ProxyHandler({}))
        for port in (48082, 48083):
            while True:
                if any(child.poll() is not None for child in self.children):
                    raise RuntimeError("业务进程提前退出")
                try:
                    with local_http.open(
                        f"http://127.0.0.1:{port + self.args.port_offset}/actuator/health/readiness",
                        timeout=2,
                    ) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise TimeoutError("业务就绪超时")
                time.sleep(1)

    def smoke(self) -> None:
        self.command(
            [
                "node",
                "tests/acceptance/smoke.cjs",
                self.root,
                "equipment-service",
                "inspection-service",
            ],
            self.root,
            300,
        )

    def cleanup(self) -> None:
        errors = []
        for child in self.children:
            try:
                stop_process(child)
            except (OSError, subprocess.SubprocessError):
                errors.append("process")
        if self.owns_environment:
            for suffix in ("apps", "infra"):
                project = f"{self.args.project}-local-{suffix}"
                try:
                    ids = self.docker_ids("container", project)
                    if ids:
                        subprocess.run(
                            ["docker", "stop", "--time", "15", *ids],
                            check=True,
                            timeout=90,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                        )
                    running = subprocess.check_output(
                        [
                            "docker",
                            "ps",
                            "--quiet",
                            "--filter",
                            f"label=com.docker.compose.project={project}",
                        ],
                        text=True,
                        timeout=30,
                    ).split()
                    if running:
                        errors.append(project)
                    for kind in ("container", "volume", "network"):
                        self.report["remaining_resources"].append(
                            {
                                "project": project,
                                "kind": kind,
                                "ids": self.docker_ids(kind, project),
                            }
                        )
                except (OSError, subprocess.SubprocessError):
                    errors.append(project)
            network = self.args.project + "-local-net"
            self.report["remaining_resources"].append(
                {
                    "kind": "external_network",
                    "name": network,
                    "ids": subprocess.check_output(
                        ["docker", "network", "ls", "--quiet", "--filter", f"name=^{network}$"],
                        text=True,
                        timeout=30,
                    ).split(),
                }
            )
        if errors:
            raise RuntimeError("本轮资源停止失败")

    def run(self) -> int:
        try:
            for name in PHASES[:-1]:
                self.phase(name, getattr(self, name))
        except (Exception, KeyboardInterrupt):
            pass
        finally:
            try:
                self.phase("cleanup", self.cleanup)
            except (Exception, KeyboardInterrupt):
                pass
            if all(item["status"] == "passed" for item in self.report["phases"].values()):
                self.report["status"] = "passed"
            content = json.dumps(self.report, ensure_ascii=False, indent=2) + "\n"
            if self.root.is_dir() and self.report["phases"]["generate"]["status"] == "passed":
                write(self.root / ".local/release-check/report.json", content)
            print(content)
        return 0 if self.report["status"] == "passed" else 1


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--port-offset", type=int, required=True)
    parser.add_argument("--dev-host", required=True)
    args = parser.parse_args()

    def terminate(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    return Verification(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
