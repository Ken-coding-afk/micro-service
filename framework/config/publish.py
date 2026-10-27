"""config/publish.py — 配置发布固定入口(设计 02·配置发布、漂移与回退)。

- 以发布凭证同步 config/nacos/*.yaml 到指定 namespace/Data ID;
- 发布前比较远端内容摘要与本地发布状态:远端被未知变更(漂移)时拒绝覆盖;
- 每次发布记录 operation_id/时间/前后摘要到 .local/publish-log.jsonl;
- --dry-run 只输出计划;应用运行身份不得使用本入口(账号分离由部署保证)。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path

import httpx

FRAMEWORK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(FRAMEWORK_ROOT / "config"))
import contract

GROUP = "DEFAULT_GROUP"


def _login(client: httpx.Client, server: str, user: str, password: str) -> str:
    resp = client.post(
        f"{server.rstrip('/')}/v3/auth/user/login",
        data={"username": user, "password": password},
    )
    if resp.status_code != 200:
        raise SystemExit(f"Nacos 登录失败: HTTP {resp.status_code}")
    token = resp.json().get("accessToken")
    if not token:
        raise SystemExit("Nacos 登录未返回 accessToken")
    return token


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _publish(args: argparse.Namespace) -> int:
    env = contract.parse_env_file(Path(args.env_file))
    issues = contract.validate_env(env)
    if issues:
        for issue in issues:
            print(f"[env] {issue}", file=sys.stderr)
        return 2
    values = env.values

    source_dir = Path(args.source)
    issues = contract.validate_nacos_source(source_dir)
    if issues:
        print("[source] 配置源校验失败,拒绝发布:", file=sys.stderr)
        for issue in issues:
            print(f"  - {issue}", file=sys.stderr)
        return 2

    state_path = Path(args.env_file).resolve().parent / "publish-state.json"
    state: dict = {}
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))

    server = values["NACOS_SERVER_ADDR"]
    namespace = values["NACOS_NAMESPACE"]
    user = values.get("NACOS_PUBLISH_USER")
    if not user or user == values.get("NACOS_RUNTIME_USER"):
        raise ValueError("必须提供与运行身份不同的 NACOS_PUBLISH_USER")
    password = contract.secret_value(values, "NACOS_PUBLISH_PASSWORD")

    operation_id = uuid.uuid4().hex[:12]
    results: list[dict] = []
    refused = False

    with httpx.Client(timeout=15) as client:
        token = _login(client, values["NACOS_CONSOLE_ADDR"], user, password)
        for path in sorted(source_dir.glob("*.yaml")):
            data_id = path.name
            content = path.read_text(encoding="utf-8")
            new_digest = _sha256(content)

            current = client.get(
                f"http://{server}/nacos/v3/admin/cs/config",
                params={
                    "accessToken": token,
                    "dataId": data_id,
                    "groupName": GROUP,
                    "namespaceId": namespace,
                },
            )
            remote_digest = None
            if current.status_code not in (200, 404):
                print(f"[publish] {data_id} 查询失败: HTTP {current.status_code}")
                refused = True
                break
            if current.status_code == 200:
                body = current.json()
                if body.get("code") != 0 or not isinstance(body.get("data"), dict):
                    print(f"[publish] {data_id} 查询返回错误，拒绝当作配置缺失")
                    refused = True
                    break
                payload = body["data"]
                remote_digest = _sha256(payload.get("content") or "")
            state_key = f"{server}/{values['PROJECT_CODE']}/{values['APP_ENV']}/{namespace}/{data_id}"
            recorded_digest = state.get(state_key)

            entry = {
                "operation_id": operation_id,
                "data_id": data_id,
                "project": values["PROJECT_CODE"],
                "environment": values["APP_ENV"],
                "namespace": namespace,
                "old_digest": remote_digest,
                "new_digest": new_digest,
                "at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            }

            if (
                remote_digest is not None
                and remote_digest != recorded_digest
                and remote_digest != new_digest
            ):
                # 远端内容既不是上次发布的,也不是待发布内容 → 漂移,拒绝覆盖
                entry["result"] = "REFUSED_DRIFT"
                print(
                    f"[publish] 拒绝覆盖 {data_id}: 检测到未知远端变更(漂移),"
                    f"请先回写 Git 源并核对基线"
                )
                results.append(entry)
                refused = True
                continue

            if remote_digest == new_digest:
                entry["result"] = "UNCHANGED"
                state[state_key] = new_digest
                results.append(entry)
                continue

            if args.dry_run:
                entry["result"] = "PLAN"
                print(f"[publish] dry-run: 将发布 {data_id} -> {namespace}")
                results.append(entry)
                continue

            resp = client.post(
                f"http://{server}/nacos/v3/admin/cs/config",
                data={
                    "accessToken": token,
                    "dataId": data_id,
                    "groupName": GROUP,
                    "namespaceId": namespace,
                    "content": content,
                    "type": "yaml",
                },
            )
            if (
                resp.status_code != 200
                or resp.json().get("code") != 0
                or resp.json().get("data") is not True
            ):
                entry["result"] = f"FAILED_HTTP_{resp.status_code}"
                print(f"[publish] {data_id} 发布失败: HTTP {resp.status_code}")
                results.append(entry)
                refused = True
                continue
            entry["result"] = "PUBLISHED"
            state[state_key] = new_digest
            print(f"[publish] 已发布 {data_id}(摘要 {new_digest[:12]})")
            results.append(entry)
            if not args.dry_run:
                state_path.parent.mkdir(parents=True, exist_ok=True)
                temporary = state_path.with_suffix(".json.tmp")
                temporary.write_text(
                    json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                temporary.replace(state_path)

    if not args.dry_run:
        state_path.parent.mkdir(exist_ok=True)
        state_path.write_text(
            json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        log_path = state_path.parent / "publish-log.jsonl"
        with log_path.open("a", encoding="utf-8") as fh:
            for entry in results:
                fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return 1 if refused else 0


def publish(args: argparse.Namespace) -> int:
    """网络/解析失败不输出可能含 token 的请求 URL，成功项摘要已逐项保存。"""
    try:
        return _publish(args)
    except (httpx.HTTPError, OSError, ValueError, KeyError) as exc:
        print(
            f"[publish] 发布中止: {type(exc).__name__}，请核对逐项摘要后恢复",
            file=sys.stderr,
        )
        return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Nacos 配置发布入口(设计 02)")
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--source", default=str(FRAMEWORK_ROOT / "config" / "nacos"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    return publish(args)


if __name__ == "__main__":
    raise SystemExit(main())
