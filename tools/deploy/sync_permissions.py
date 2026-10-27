"""通过标准 OAuth2 发布权限目录；不写身份库、不授予用户权限。"""

import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import yaml


def origin(value: str, app_env: str) -> str:
    parsed = urlsplit(value)
    local = parsed.hostname == "localhost"
    try:
        local = local or ipaddress.ip_address(parsed.hostname or "").is_loopback
    except ValueError:
        pass
    if (
        not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
        or (
            parsed.scheme != "https"
            and not (app_env == "local" and local and parsed.scheme == "http")
        )
    ):
        raise ValueError("身份地址须为 HTTPS origin；仅 local 允许 loopback HTTP")
    return value.rstrip("/")


def checked(response: httpx.Response) -> dict:
    if response.status_code >= 300:
        # 不打印响应正文、URL、请求头，避免错误页带出凭证。
        raise ValueError(
            f"身份接口失败：HTTP {response.status_code}；停止发布，检查身份服务审计"
        )
    result = response.json()
    if not isinstance(result, dict):
        raise TypeError("身份接口返回格式不正确")
    return result


def publish(
    client: httpx.Client,
    base: str,
    client_id: str,
    secret: str,
    manifest: Path,
    release: str,
) -> dict:
    raw = manifest.read_bytes()
    if len(raw) > 1_048_576:
        raise ValueError("权限清单不得超过 1 MiB")
    content = raw.decode("utf-8")
    document = yaml.safe_load(content)
    if not isinstance(document, dict) or not isinstance(document.get("service"), str):
        raise TypeError("权限清单缺少 service")
    service = document["service"]
    token = checked(
        client.post(
            base + "/oauth2/token",
            auth=httpx.BasicAuth(client_id, secret),
            data={
                "grant_type": "client_credentials",
                "scope": "catalog.publish:" + service,
            },
        )
    ).get("access_token")
    if not isinstance(token, str) or not token:
        raise ValueError("授权服务器未返回 access_token")
    headers = {"Authorization": "Bearer " + token}
    version = checked(
        client.get(base + "/internal/v1/permission-catalogs/version", headers=headers)
    )["data"]["catalog_version"]
    return checked(
        client.post(
            base + "/internal/v1/permission-catalogs:sync",
            headers=headers,
            json={
                "service": service,
                "release_version": release,
                "checksum": hashlib.sha256(raw).hexdigest(),
                "content": content,
                "catalog_version": version,
            },
        )
    )["data"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-origin", required=True)
    parser.add_argument("--app-env", choices=("local", "test", "prod"), required=True)
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--release-version", required=True)
    args = parser.parse_args()
    try:
        base = origin(args.identity_origin, args.app_env)
        secret = args.secret_file.read_text(encoding="utf-8").strip()
        if not secret:
            raise ValueError("发布凭证为空")
        with httpx.Client(timeout=10, follow_redirects=False) as client:
            publish(
                client,
                base,
                args.client_id,
                secret,
                args.manifest,
                args.release_version,
            )
        print(
            json.dumps({"status": "published", "release_version": args.release_version})
        )
        return 0
    except (ValueError, TypeError, OSError, KeyError, yaml.YAMLError, httpx.HTTPError):
        print(
            "权限发布失败；未自动重试。检查地址、凭证、清单及身份服务审计后重新执行。"
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
