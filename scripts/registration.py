"""服务接入文件与命名规则；不携带业务样例源码。"""

from __future__ import annotations

import copy
import json
import keyword
import re
from pathlib import Path

from common import FRAMEWORK, read_yaml, write, write_yaml


def validate_name(name: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name) or len(name) > 30:
        raise ValueError("名称须为不超过 30 字符的小写连字符标识")
    if name in {"identity-service", "api-gateway", "nginx", "con", "nul", "aux", "prn"}:
        raise ValueError("名称与框架或系统保留名称冲突")


def validate_package(package: str, language: str) -> None:
    pattern = r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+" if language == "java" else r"[a-z][a-z0-9_]*"
    if not re.fullmatch(pattern, package) or any(keyword.iskeyword(x) for x in package.split(".")):
        raise ValueError("包名不合法")
    if language == "java" and any(
        x in {"class", "interface", "enum", "package", "import", "public", "void", "int", "new"}
        for x in package.split(".")
    ):
        raise ValueError("Java 包名不能使用关键字")


def replacements(language: str, name: str, package: str, peer: str | None) -> dict[str, str]:
    other = "python" if language == "java" else "java"
    result = {
        f"sample-{language}-service": name,
        f"sample-{language}-db": name + "-db",
        f"sample_{language}": name.replace("-", "_"),
        f"/api/v1/{language}-items": f"/api/v1/{name}/items",
        f"{language}-item:": f"{name}-item:",
        f"{language}.items.": f"{name}.items.",
        f"resource_code: {language}-item": f"resource_code: {name}-item",
    }
    if language == "java":
        result |= {"com.example.sample": package, "com/example/sample": package.replace(".", "/")}
    else:
        result |= {"sample_service": package, "sample-api": name + "-api"}
    if peer:
        result |= {
            f"sample-{other}-service": peer,
            f"/api/v1/{other}-items": f"/api/v1/{peer}/items",
        }
    return result


def replace(text: str, values: dict[str, str]) -> str:
    for old, new in sorted(values.items(), key=lambda pair: -len(pair[0])):
        text = text.replace(old, new)
    return text


def declaration(
    language: str, name: str, package: str, peer: str | None, source: str = "local"
) -> dict:
    base = f"services/{name}"
    record = {
        "source": source,
        "language": language,
        "directory": base,
        "package": package,
        "resources": base + "/resources.yaml",
        "permissions": base
        + (
            "/src/main/resources/permissions.yaml"
            if language == "java"
            else f"/src/{package}/resources/permissions.yaml"
        ),
        "migration": {
            "tool": "flyway" if language == "java" else "alembic",
            "path": base
            + ("/src/main/resources/db/migration" if language == "java" else "/migrations"),
        },
        "requires": [],
        "peer": peer,
    }
    if language == "java":
        record["main_class"] = package + ".SampleApplication"
    else:
        record |= {
            "entry": name + "-api",
            "debug_module": package + ".main",
            "app_factory": package + ".main:create_app",
        }
    return record


def register(
    root: Path,
    language: str,
    name: str,
    package: str,
    port: int,
    peer: str | None,
    source: str = "local",
    seed_root: Path | None = None,
) -> None:
    catalog_path = root / "config/services.yaml"
    catalog = read_yaml(catalog_path)
    development = read_yaml(root / "config/development.yaml")
    if (
        name in catalog["services"]
        or not 1024 <= port <= 65535
        or port in [v["port"] for v in development["services"].values()]
    ):
        raise ValueError("服务名或端口冲突/非法")
    catalog["services"][name] = declaration(language, name, package, peer, source)
    development["services"][name] = {"port": port}
    write_yaml(catalog_path, catalog)
    write_yaml(root / "config/development.yaml", development)
    values = replacements(language, name, package, peer)
    for path in ((seed_root or FRAMEWORK) / "config/nacos").glob(
        f"{name}*" if source == "submodule" else f"sample-{language}-service*"
    ):
        write(
            root / "config/nacos" / replace(path.name, values),
            replace(path.read_text(encoding="utf-8"), values),
        )
    gateway_path = root / "config/nacos/api-gateway.yaml"
    gateway = read_yaml(gateway_path)
    route = copy.deepcopy(
        {
            "id": name + "-items",
            "uri": "lb://" + name,
            "predicates": ["Path=/api/v1/" + name + "/**"],
            "metadata": {"platform.audience": name},
            "filters": [
                {
                    "name": "RequestRateLimiter",
                    "args": {
                        "key-resolver": "#{@tenantOrClientKeyResolver}",
                        "rate-limiter": "#{@platformRedisRateLimiter}",
                        "redis-rate-limiter.replenishRate": 20,
                        "redis-rate-limiter.burstCapacity": 40,
                        "redis-rate-limiter.requestedTokens": 1,
                    },
                }
            ],
        }
    )
    route = json.loads(replace(json.dumps(route), values))
    route["id"] = name + "-items"
    route["predicates"] = [f"Path=/api/v1/{name}/**"]
    routes = gateway["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
    if any(r["id"] == route["id"] for r in routes):
        raise ValueError("网关路由重名")
    routes.append(route)
    write_yaml(gateway_path, gateway)
    if language == "java":
        pom = root / "pom.xml"
        write(
            pom,
            pom.read_text(encoding="utf-8").replace(
                "</modules>", f"  <module>services/{name}</module>\n  </modules>"
            ),
        )
    from build import business_compose
    from catalog import project_config

    write_yaml(root / "deploy/generated.services.yaml", business_compose(project_config(root)))
