"""本地业务启动：登记目录 + 原运行绑定 + 受限宿主地址覆盖，不安装依赖或修改数据。"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/config"))
from contract import parse_env_file  # noqa: E402

HOST_FIELDS = {
    "SERVICE_BIND_HOST",
    "SERVICE_BIND_PORT",
    "SERVICE_ADVERTISE_HOST",
    "SERVICE_ADVERTISE_PORT",
    "PG_HOST",
    "PG_PORT",
    "REDIS_HOST",
    "REDIS_PORT",
    "NACOS_SERVER_ADDR",
    "IDENTITY_INTERNAL_ORIGIN",
    "S3_ENDPOINT",
    "RABBIT_HOST",
    "RABBIT_PORT",
    "NO_PROXY",
}
SYSTEM_FIELDS = {
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "WINDIR",
    "TEMP",
    "TMP",
    "HOME",
    "USERPROFILE",
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
}


def prepare(root: Path, service: str, env_file: Path, host_file: Path):
    base = parse_env_file(env_file.resolve()).values
    override = parse_env_file(host_file.resolve()).values
    if base.get("APP_ENV") != "local" or base.get("SERVICE_NAME") != service:
        raise ValueError("仅允许指定服务的 local 运行绑定")
    forbidden = ("ADMIN", "MIGRAT", "PUBLISH", "BOOTSTRAP")
    if any(any(word in key for word in forbidden) for key in base):
        raise ValueError("业务进程不能装载管理/迁移/发布绑定")
    allowed = HOST_FIELDS | {key for key in base if key.endswith("_SECRET_FILE")}
    if set(override) - allowed:
        raise ValueError("宿主覆盖包含未允许字段")
    values = base | override
    if "*" in values.get("NO_PROXY", ""):
        raise ValueError("本地代理例外必须列出明确主机")
    if values.get("NACOS_NAMESPACE") != values.get("PROJECT_CODE", "") + "-local":
        raise ValueError("本地 namespace 与项目不一致")
    for key in ("SERVICE_BIND_PORT", "SERVICE_ADVERTISE_PORT", "PG_PORT"):
        if key in values and (
            not values[key].isdigit() or not 1 <= int(values[key]) <= 65535
        ):
            raise ValueError("端口不合法")
    for key, value in values.items():
        if key.endswith("_SECRET_FILE") and not Path(value).is_file():
            raise ValueError("本地进程秘密引用不存在")
    catalog = yaml.safe_load(
        (root / "config/development.yaml").read_text(encoding="utf-8")
    )
    item = catalog["services"][service]
    directory = (root / item["directory"]).resolve()
    if not directory.is_relative_to(root.resolve()) or not directory.is_dir():
        raise ValueError("业务目录不存在或越界")
    if item["language"] == "java":
        jar = (directory / item["entry"]).resolve()
        if (
            not jar.is_relative_to(directory)
            or not jar.is_file()
            or jar.suffix != ".jar"
        ):
            raise ValueError("先构建登记的业务 JAR")
        command = ["java", "-jar", str(jar)]
    elif item["language"] == "python" and re.fullmatch(
        r"[a-zA-Z][a-zA-Z0-9_-]*", item["entry"]
    ):
        interpreter = directory / (
            (".venv/Scripts/" + item["entry"] + ".exe")
            if os.name == "nt"
            else (".venv/bin/" + item["entry"])
        )
        if not interpreter.is_file():
            raise ValueError("先安装业务自己的 Python 环境")
        command = [str(interpreter)]
    else:
        raise ValueError("未登记的业务启动类型")
    child = {
        key: value for key, value in os.environ.items() if key.upper() in SYSTEM_FIELDS
    }
    return directory, command, child | values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True)
    parser.add_argument("--role", choices=["api"], default="api")
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--host-env-file", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        directory, command, child = prepare(
            ROOT, args.service, args.env_file, args.host_env_file
        )
        print(
            json.dumps(
                {
                    "service": args.service,
                    "environment": "local",
                    "advertise_host": child.get("SERVICE_ADVERTISE_HOST"),
                    "advertise_port": child.get("SERVICE_ADVERTISE_PORT"),
                }
            ),
            flush=True,
        )
        if args.check:
            return 0
        return subprocess.run(command, cwd=directory, env=child, check=False).returncode
    except (ValueError, KeyError, TypeError, OSError, yaml.YAMLError):
        print(
            "本地启动拒绝：核对服务登记、运行绑定、允许覆盖与业务构建", file=sys.stderr
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
