"""本机双网关共享额度验收：先按 README 设置测试桶并重启两个网关。"""

import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-a", required=True)
    parser.add_argument("--gateway-b", required=True)
    parser.add_argument("--cookies", type=Path, required=True)
    parser.add_argument("--other-tenant-cookies", type=Path, required=True)
    args = parser.parse_args()
    for origin in (args.gateway_a, args.gateway_b):
        parsed = urlsplit(origin)
        if (
            parsed.scheme != "http"
            or parsed.hostname not in {"localhost", "127.0.0.1"}
            or parsed.username
            or parsed.password
            or parsed.path
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("仅允许本机隔离示例网关 origin")
    assert args.gateway_a != args.gateway_b

    def cookies(path):
        return {
            item["name"]: item["value"]
            for item in json.loads(path.read_text(encoding="utf-8"))["cookies"]
        }

    path = "/bff/web/api/v1/java-items"
    with httpx.Client(timeout=10, trust_env=False) as client:
        for cookie_file in (args.cookies, args.other_tenant_cookies):
            first = client.get(args.gateway_a + path, cookies=cookies(cookie_file))
            assert first.status_code == 200, "每个租户的初始请求必须有独立额度"
            second = client.get(
                args.gateway_b + path,
                cookies=cookies(cookie_file),
                headers={"X-Tenant-Id": "forged-tenant"},
            )
            assert second.status_code == 429 and second.json()["code"] == "RATE_LIMITED"
            assert int(second.headers["Retry-After"]) >= 1
            print(
                "PASS same tenant shares bucket across two Gateway processes; forged header has no effect"
            )
        invalid = client.get(
            args.gateway_b + "/api/v1/java-items",
            headers={"Authorization": "Bearer invalid-fixture"},
        )
        assert invalid.status_code == 401, "无效令牌必须先拒绝，不能被额度错误覆盖"
        print(
            "PASS separate tenants receive separate buckets; invalid token rejected before quota"
        )


if __name__ == "__main__":
    main()
