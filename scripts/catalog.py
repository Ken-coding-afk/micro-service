"""项目服务声明与选择；配置只在文件中维护一次，不保存第二份运行目录。"""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import urlsplit

import yaml
from common import read_yaml

BASE_SERVICES = {"identity-service", "api-gateway"}


def nacos_content(root: Path, path: Path, selected: list[str]) -> str:
    """派生本次发布的网关路由；源文件不改，既有已初始化服务继续保留。"""
    content = path.read_text(encoding="utf-8")
    if path.name != "api-gateway.yaml":
        return content
    declared = set(services(root)) - BASE_SERVICES
    active = set(selected)
    readers = root / ".local/runtime/api-gateway-secrets/readers.json"
    if readers.exists():
        document = json.loads(readers.read_text(encoding="utf-8"))
        if not isinstance(document, dict) or not isinstance(document.get("readers"), list):
            raise ValueError("既有网关内省清单格式无效")
        for reader in document["readers"]:
            if not isinstance(reader, dict) or not isinstance(reader.get("audience"), str):
                raise ValueError("既有网关内省清单格式无效")
            active.add(reader["audience"])
    document = yaml.safe_load(content)
    branch = document
    for key in ("spring", "cloud", "gateway", "server", "webflux"):
        if not isinstance(branch, dict) or key not in branch:
            return content
        branch = branch[key]
    if not isinstance(branch, dict) or not isinstance(branch.get("routes"), list):
        return content
    routes = []
    for route in branch["routes"]:
        target = None
        if isinstance(route, dict) and isinstance(route.get("uri"), str):
            try:
                uri = urlsplit(route["uri"])
                if uri.scheme == "lb":
                    target = uri.hostname
            except ValueError:
                pass  # 非法源配置仍交既有校验拒绝，不在选择过程中修正。
        if target not in declared or target in active:
            routes.append(route)
    if len(routes) == len(branch["routes"]):
        return content
    branch["routes"] = routes
    return yaml.safe_dump(document, sort_keys=False, allow_unicode=True)


def nacos_files(root: Path, selected: list[str]) -> list[Path]:
    """只选择共享配置及已选服务的配置；保留项目自定义共享 Data ID。"""
    names = sorted(services(root), key=len, reverse=True)
    result = []
    for path in sorted((root / "config/nacos").iterdir()):
        if path.suffix not in {".yaml", ".json"}:
            continue
        owner = next(
            (
                name
                for name in names
                if path.stem == name or path.stem.startswith(name + "-sentinel-")
            ),
            None,
        )
        if owner is None or owner in selected:
            result.append(path)
    return result


FIELDS = {
    "source",
    "language",
    "directory",
    "package",
    "main_class",
    "entry",
    "debug_module",
    "app_factory",
    "resources",
    "permissions",
    "migration",
    "requires",
    "peer",
}


def inside(root: Path, value: str) -> Path:
    if not isinstance(value, str) or Path(value).is_absolute():
        raise ValueError("声明必须使用项目相对路径")
    path = (root / value).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("声明路径越过项目根")
    return path


def services(root: Path) -> dict:
    data = read_yaml(root / "config/services.yaml")
    if not isinstance(data, dict) or set(data) != {"services"}:
        raise ValueError("services.yaml 顶层只允许 services")
    records = data["services"]
    if not isinstance(records, dict) or not BASE_SERVICES <= records.keys():
        raise ValueError("缺少基础服务声明")
    for name, record in records.items():
        if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name):
            raise ValueError("服务名称不合法")
        if not isinstance(record, dict) or set(record) - FIELDS:
            raise ValueError(f"服务声明包含未知字段：{name}")
        if record.get("source") not in {"local", "submodule"}:
            raise ValueError(f"服务来源不合法：{name}")
        if record.get("language") not in {"java", "python"}:
            raise ValueError(f"服务语言不合法：{name}")
        if record.get("directory") != f"services/{name}":
            raise ValueError(f"服务目录不匹配：{name}")
        inside(root, record["directory"])
        for field in ("resources", "permissions"):
            if record.get(field):
                inside(root, record[field])
        if record.get("migration"):
            value = record["migration"]
            if set(value) != {"tool", "path"} or value["tool"] not in {"flyway", "alembic"}:
                raise ValueError(f"迁移声明不合法：{name}")
            inside(root, value["path"])
        requires = record.get("requires", [])
        if not isinstance(requires, list) or any(dep not in records for dep in requires):
            raise ValueError(f"未知强依赖：{name}")
    select(records, "all")  # 同时检查环。
    return records


def select(records: dict, requested: str) -> list[str]:
    names = list(records) if requested == "all" else requested.split(",")
    if not names or any(name not in records for name in names):
        raise ValueError("必须选择已登记的服务名或 all")
    result, active = [], set()

    def visit(name: str) -> None:
        if name in active:
            raise ValueError("服务强依赖成环")
        if name in result:
            return
        active.add(name)
        for dep in records[name].get("requires", []):
            visit(dep)
        active.remove(name)
        result.append(name)

    for name in ["identity-service", "api-gateway", *names]:
        visit(name)
    return result


def project_config(root: Path, requested: str = "all") -> dict:
    """为共用构建/运行函数组装内存视图；基础服务不混入业务遍历。"""
    records = services(root)
    development = read_yaml(root / "config/development.yaml")
    local = development["local"]
    if set(local) - {"project", "port_offset", "dev_host"}:
        raise ValueError("development.local 只保存开发环境设置")
    ports = development["services"]
    used = set()
    for name, record in ports.items():
        if name not in records or set(record) != {"port"}:
            raise ValueError("development.services 只保存已登记服务的 port")
        port = record["port"]
        if type(port) is not int or not 1024 <= port <= 65535 or port in used:
            raise ValueError("开发端口非法或冲突")
        used.add(port)
    selected = select(records, requested)
    business = {}
    for name in selected:
        if name in BASE_SERVICES:
            continue
        if name not in ports:
            raise ValueError(f"缺少开发端口：{name}")
        record = records[name] | ports[name]
        record.setdefault("peer", None)
        if record["language"] == "java":
            record["entry"] = f"target/{name}.jar"
        business[name] = record
    return {"local": local, "services": business, "selected": selected}


def database_resource(root: Path, record: dict) -> dict:
    declarations = read_yaml(root / record["resources"])["resources"]
    databases = [value for value in declarations if value["component"] == "postgresql"]
    if len(databases) != 1:
        raise ValueError("便捷入口要求服务声明唯一主数据库；多库分别使用底层迁移入口")
    return databases[0]
