"""把本地示例 API 登记结果转换为业务绑定；不访问身份库，不适用于正式项目。"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_local import contract, create_once, env_file


def runtime_values(path: Path) -> dict[str, str]:
    # 容器中的 /run/secrets 路径不能按 Windows 宿主机路径解析。
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in result:
            raise ValueError("运行绑定格式不合法")
        result[key] = value
    return result


def bind(env_path: Path, java_image: str, python_image: str):
    env = contract.parse_env_file(env_path).values
    if env.get("APP_ENV") != "local" or env.get("PG_RESOURCE_PREFIX"):
        raise ValueError("仅支持本地示例默认资源命名")
    runtime = env_path.parent / "runtime"
    state = json.loads(
        (env_path.parent / "business-credentials.json").read_text(encoding="utf-8")
    )
    common = runtime_values(runtime / "identity-service.env")
    common = {
        key: value
        for key, value in common.items()
        if not key.startswith(("DB_", "IDENTITY_", "REDIS_", "SERVICE_"))
    }
    prefix = env["PROJECT_CODE"].replace("-", "_") + "_local_"
    gateway = runtime / "api-gateway-secrets"
    readers = []
    for lang, port in (("java", 8082), ("python", 8083)):
        service = f"sample-{lang}-service"
        secret_dir = runtime / f"{service}-secrets"
        create_once(
            secret_dir / "nacos-runtime.password",
            contract.secret_value(env, "NACOS_RUNTIME_PASSWORD"),
        )
        create_once(
            secret_dir / "database.password",
            (env_path.parent / f"secrets/sample-{lang}-db-runtime.password").read_text(
                encoding="utf-8"
            ),
        )
        create_once(
            secret_dir / "introspection.password",
            state[lang + "-resource"]["client_secret"],
        )
        create_once(
            gateway / f"{lang}-reader.password",
            state[lang + "-gateway"]["client_secret"],
        )
        readers.append(
            {
                "audience": service,
                "client_id": f"sample-{lang}-gateway",
                "secret_file": f"/run/secrets/{lang}-reader.password",
            }
        )
        env_file(
            runtime / f"{service}.env",
            {
                **common,
                "SERVICE_NAME": service,
                "SERVICE_BIND_HOST": "0.0.0.0",
                "SERVICE_BIND_PORT": port,
                "SERVICE_ADVERTISE_HOST": service,
                "SERVICE_ADVERTISE_PORT": port,
                "SERVICE_SCHEME": "http",
                "DB_NAME": prefix + "sample_" + lang,
                "DB_USER": prefix + "sample_" + lang + "_runtime",
                "DB_PASSWORD_SECRET_FILE": "/run/secrets/database.password",
                "RESOURCE_AUDIENCE": service,
                "INTROSPECTION_CLIENT_ID": f"sample-{lang}-resource",
                "INTROSPECTION_CLIENT_SECRET_FILE": "/run/secrets/introspection.password",
                "PEER_SERVICE": "sample-python-service"
                if lang == "java"
                else "sample-java-service",
            },
        )
    create_once(gateway / "readers.json", json.dumps({"readers": readers}, indent=2))
    create_once(gateway / "web.password", state["web"]["client_secret"])
    bff_path = gateway / "bff.json"
    bff = json.loads(bff_path.read_text(encoding="utf-8"))
    web = {
        "secret_file": "/run/secrets/web.password",
        "requested_scopes": [
            "profile",
            "java.items.read",
            "java.items.write",
            "python.items.read",
            "python.items.write",
        ],
    }
    if "web" in bff and bff["web"] != web:
        raise ValueError("已有 web 绑定不同，拒绝覆盖")
    bff["web"] = web
    # 此入口唯一允许的更新是加入示例 web 与 readers；其他绑定不动。
    bff_path.write_text(json.dumps(bff, indent=2), encoding="utf-8")
    for name, additions in (
        (
            "api-gateway.env",
            {"GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE": "/run/secrets/readers.json"},
        ),
        (
            "compose.env",
            {"SAMPLE_JAVA_IMAGE": java_image, "SAMPLE_PYTHON_IMAGE": python_image},
        ),
    ):
        file = runtime / name
        values = runtime_values(file)
        if any(
            key in values and values[key] != value for key, value in additions.items()
        ):
            raise ValueError("已有示例绑定不同，拒绝覆盖")
        values.update(additions)
        file.write_text(
            "".join(f"{key}={value}\n" for key, value in values.items()),
            encoding="utf-8",
        )
    print("本地业务绑定已生成；配置发布与容器启动仍需明确执行。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--java-image", required=True)
    parser.add_argument("--python-image", required=True)
    args = parser.parse_args()
    try:
        bind(args.env_file.resolve(), args.java_image, args.python_image)
    except (OSError, ValueError, KeyError):
        raise SystemExit("本地业务绑定失败：检查已登记客户端和资源文件；已有秘密保留。")
