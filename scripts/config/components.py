"""发布前校验成熟组件配置；运行时仍由各组件原生绑定与执行。"""

import json
import math
import re
from pathlib import Path


def positive_int(value):
    return type(value) is int and 0 < value <= 2147483647


def component_issues(doc: dict, filename: str) -> list[str]:
    try:
        if "gateway" in doc:
            return [
                f"{filename}: gateway.routes 已退役，请迁移到 spring.cloud.gateway.server.webflux.routes"
            ]
        if "spring" in doc:
            spring = doc["spring"]
            if not (isinstance(spring, dict) and set(spring) == {"cloud"}):
                raise ValueError("组件字段不合法")
            cloud = spring["cloud"]
            if not (isinstance(cloud, dict)):
                raise ValueError("组件字段不合法")
            if filename == "api-gateway.yaml":
                if not (set(cloud) == {"gateway"}):
                    raise ValueError("组件字段不合法")
                node = cloud["gateway"]
                for key in ("server", "webflux", "routes"):
                    if not (isinstance(node, dict) and set(node) == {key}):
                        raise ValueError("组件字段不合法")
                    node = node[key]
                routes(node)
            else:
                if not (
                    filename != "application-shared.yaml" and set(cloud) == {"sentinel"}
                ):
                    raise ValueError("组件字段不合法")
                if not (set(cloud["sentinel"]) == {"enabled"}):
                    raise ValueError("组件字段不合法")
                if type(cloud["sentinel"]["enabled"]) is not bool:
                    raise ValueError("组件字段不合法")
        if "concurrency" in doc:
            if not (
                filename
                not in (
                    "application-shared.yaml",
                    "api-gateway.yaml",
                    "identity-service.yaml",
                )
            ):
                raise ValueError("组件字段不合法")
            c = doc["concurrency"]
            if not (
                isinstance(c, dict) and set(c) <= {"inbound_limit", "outbound_limits"}
            ):
                raise ValueError("组件字段不合法")
            if not (c.get("inbound_limit") is None or positive_int(c["inbound_limit"])):
                raise ValueError("组件字段不合法")
            limits = c.get("outbound_limits", {})
            if not (isinstance(limits, dict) and len(limits) <= 64):
                raise ValueError("组件字段不合法")
            if not (
                all(
                    re.fullmatch(r"[a-z][a-z0-9-]{0,62}", k) and positive_int(v)
                    for k, v in limits.items()
                )
            ):
                raise ValueError("组件字段不合法")
        http = doc.get("http", {})
        for field in ("max_connections", "max_keepalive_connections"):
            if not (field not in http or positive_int(http[field])):
                raise ValueError("组件字段不合法")
        if not (
            http.get("max_keepalive_connections", 32) <= http.get("max_connections", 64)
        ):
            raise ValueError("组件字段不合法")
    except (AssertionError, KeyError, TypeError, AttributeError, ValueError):
        return [f"{filename}: 组件配置字段、类型或安全边界不合法"]
    return []


def routes(items):
    if not (isinstance(items, list) and len(items) <= 64):
        raise ValueError("组件字段不合法")
    ids, paths = set(), set()
    for r in items:
        if not (
            isinstance(r, dict)
            and set(r) <= {"id", "uri", "predicates", "filters", "metadata"}
        ):
            raise ValueError("组件字段不合法")
        if not (
            re.fullmatch(r"[a-z][a-z0-9-]{0,79}", r["id"])
            and not r["id"].startswith("framework-")
        ):
            raise ValueError("组件字段不合法")
        if not (r["id"] not in ids):
            raise ValueError("组件字段不合法")
        ids.add(r["id"])
        if not (
            re.fullmatch(r"lb://[a-z][a-z0-9-]{0,127}", r["uri"])
            and r["uri"] != "lb://api-gateway"
        ):
            raise ValueError("组件字段不合法")
        predicates = r["predicates"]
        if not (isinstance(predicates, list) and len(predicates) == 1):
            raise ValueError("组件字段不合法")
        p = predicates[0]
        if isinstance(p, str):
            if not (p.startswith("Path=")):
                raise ValueError("组件字段不合法")
            path = p[5:]
        else:
            if not (
                isinstance(p, dict)
                and set(p) == {"name", "args"}
                and p["name"] == "Path"
            ):
                raise ValueError("组件字段不合法")
            if not (
                isinstance(p["args"], dict)
                and set(p["args"]) in ({"pattern"}, {"_genkey_0"})
            ):
                raise ValueError("组件字段不合法")
            path = next(iter(p["args"].values()))
        if not (
            re.fullmatch(r"/api/v1/[a-z][a-z0-9-]*/\*\*", path) and path not in paths
        ):
            raise ValueError("组件字段不合法")
        paths.add(path)
        if not (path != "/api/v1/identity/**" or r["uri"] == "lb://identity-service"):
            raise ValueError("组件字段不合法")
        metadata = r["metadata"]
        if not (
            isinstance(metadata, dict)
            and set(metadata) <= {"platform.audience", "platform.streaming"}
        ):
            raise ValueError("组件字段不合法")
        if not (
            re.fullmatch(
                r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}", metadata["platform.audience"]
            )
        ):
            raise ValueError("组件字段不合法")
        if not (
            "platform.streaming" not in metadata
            or type(metadata["platform.streaming"]) is bool
        ):
            raise ValueError("组件字段不合法")
        filters = r.get("filters", [])
        if not (isinstance(filters, list) and len(filters) <= 1):
            raise ValueError("组件字段不合法")
        for f in filters:
            if not (set(f) == {"name", "args"} and f["name"] == "RequestRateLimiter"):
                raise ValueError("组件字段不合法")
            a = f["args"]
            if not (
                set(a)
                == {
                    "key-resolver",
                    "rate-limiter",
                    "redis-rate-limiter.replenishRate",
                    "redis-rate-limiter.burstCapacity",
                    "redis-rate-limiter.requestedTokens",
                }
            ):
                raise ValueError("组件字段不合法")
            if not (
                a["key-resolver"] == "#{@tenantOrClientKeyResolver}"
                and a["rate-limiter"] == "#{@platformRedisRateLimiter}"
            ):
                raise ValueError("组件字段不合法")
            if not (
                all(
                    positive_int(a[k]) for k in a if k.startswith("redis-rate-limiter.")
                )
            ):
                raise ValueError("组件字段不合法")
            if not (
                a["redis-rate-limiter.requestedTokens"]
                <= a["redis-rate-limiter.burstCapacity"]
            ):
                raise ValueError("组件字段不合法")


def sentinel_rules(text: str, kind: str):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("重复规则字段")
            result[key] = value
        return result

    data = json.loads(
        text,
        object_pairs_hook=unique,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("非有限数值")),
    )
    if not (isinstance(data, list) and len(data) <= 65):
        raise ValueError("组件字段不合法")
    names = set()
    for rule in data:
        if not (isinstance(rule, dict)):
            raise TypeError("组件规则必须为对象")
        allowed = {"resource", "limitApp", "grade", "count"} | (
            {"strategy", "controlBehavior", "clusterMode"}
            if kind == "flow"
            else {"minRequestAmount", "statIntervalMs", "timeWindow"}
        )
        if not (set(rule) <= allowed):
            raise ValueError("组件字段不合法")
        name = rule["resource"]
        if not (
            isinstance(name, str)
            and re.fullmatch(r"http\.inbound|http\.outbound/[a-z][a-z0-9-]{0,62}", name)
            and name not in names
        ):
            raise ValueError("组件字段不合法")
        names.add(name)
        if not (rule.get("limitApp", "default") == "default"):
            raise ValueError("组件字段不合法")
        if not (
            type(rule["grade"]) is int and rule["grade"] == (0 if kind == "flow" else 1)
        ):
            raise ValueError("组件字段不合法")
        count = rule["count"]
        if not (type(count) in (int, float) and math.isfinite(count) and count > 0):
            raise ValueError("组件字段不合法")
        if kind == "flow":
            if not (count <= 2147483647 and count == int(count)):
                raise ValueError("组件字段不合法")
            if not (
                type(rule.get("clusterMode", False)) is bool
                and not rule.get("clusterMode", False)
            ):
                raise ValueError("组件字段不合法")
            for field in ("strategy", "controlBehavior"):
                if not (type(rule.get(field, 0)) is int and rule.get(field, 0) == 0):
                    raise ValueError("组件字段不合法")
        else:
            if not (name.startswith("http.outbound/") and count <= 1):
                raise ValueError("组件字段不合法")
            if not (
                all(
                    positive_int(rule[k])
                    for k in ("minRequestAmount", "statIntervalMs", "timeWindow")
                )
            ):
                raise ValueError("组件字段不合法")
    if sum(name.startswith("http.outbound/") for name in names) > 64:
        raise ValueError("依赖规则不得超过 64 项")
    return data


def rule_source_issues(source: Path) -> list[str]:
    import yaml

    issues = []
    for path in sorted(source.glob("*.json")):
        match = re.fullmatch(
            r"([a-z][a-z0-9-]*)-sentinel-(flow|degrade)\.json", path.name
        )
        try:
            if not (match is not None and (source / f"{match[1]}.yaml").is_file()):
                raise ValueError("组件字段不合法")
            sentinel_rules(path.read_text(encoding="utf-8"), match[2])
        except (ValueError, AssertionError, KeyError, TypeError):
            issues.append(f"{path.name}: Sentinel 原生规则 JSON 不合法")
    for path in sorted(source.glob("*.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            enabled = (
                doc.get("spring", {})
                .get("cloud", {})
                .get("sentinel", {})
                .get("enabled")
            )
            if enabled is True:
                for kind in ("flow", "degrade"):
                    if not (source / f"{path.stem}-sentinel-{kind}.json").is_file():
                        issues.append(f"{path.name}: 缺少必需 Sentinel {kind} 规则文件")
        except (yaml.YAMLError, AttributeError):
            pass  # YAML 主校验提供错误。
    return issues
