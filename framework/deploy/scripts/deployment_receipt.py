"""分别记录部署尝试和成功范围；兼容恢复不改变数据库 schema。"""

import argparse
import hashlib
import json
import os
import tempfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

import yaml
from verify_release import rollback_policy


def receipt(
    release: dict, manifest: bytes, now: datetime, targets: dict | None = None
) -> dict:
    rollback_policy(release)
    deadline = now + timedelta(hours=release["rollback_window_hours"])
    return {
        "status": "completed",
        "release": release["id"],
        "project": release["project"],
        "environment": release["environment"],
        "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        "images": {
            name: image
            for name, image in release["images"].items()
            if targets is None or any(name in services for services in targets.values())
        },
        "targets": targets or {},
        "completed_at": now.isoformat(),
        "rollback_window_hours": release["rollback_window_hours"],
        "rollback_deadline": deadline.isoformat(),
        "contract_not_before": deadline.isoformat(),
        "compatible_previous_versions": release["compatible_previous_versions"],
        "owner": release["owner"],
        "approval_reference": release["approval_reference"],
    }


def check_rollback(
    target: dict,
    current: dict,
    source: str,
    now: datetime,
    attempt: dict | None = None,
    targets: dict | None = None,
) -> None:
    if attempt and attempt.get("status") == "in_progress":
        baseline = attempt.get("baseline")
        if (
            not baseline
            or baseline["release"] != target["id"]
            or attempt.get("schema_release", attempt["release"]) != source
            or attempt["project"] != target["project"]
            or attempt["environment"] != target["environment"]
            or target["id"] not in attempt["compatible_previous_versions"]
            or source not in target["compatible_schema_from"]
            or target["migrations"]
        ):
            raise ValueError("失败发行与已记录成功基线/schema 兼容声明不匹配")
        if targets is None or any(
            not set(services).issubset(targets.get(host, []))
            for host, services in attempt["targets"].items()
        ):
            raise ValueError("恢复范围必须覆盖失败发行可能更新的全部实例")
        for host, services in attempt["targets"].items():
            for name in services:
                previous = (
                    baseline["deployments"].get(host, {}).get(name, {}).get("image")
                    if baseline.get("deployments")
                    else baseline["images"].get(name)
                )
                if target["images"].get(name) != previous:
                    raise ValueError(
                        "恢复镜像必须与记录的成功基线一致；新增服务需另行核对移除"
                    )
        start = datetime.fromisoformat(attempt["started_at"])
        if start.utcoffset() != timedelta(
            0
        ) or not start <= now < datetime.fromisoformat(attempt["recovery_deadline"]):
            raise ValueError("失败发行已超出确认的恢复窗口")
        return
    if (
        current["status"] != "completed"
        or current.get("schema_release", current["release"]) != source
        or current["project"] != target["project"]
        or current["environment"] != target["environment"]
        or target["id"] not in current["compatible_previous_versions"]
        or source not in target["compatible_schema_from"]
        or target["migrations"]
    ):
        raise ValueError("当前成功发行、环境或双向兼容声明不匹配")
    completed = datetime.fromisoformat(current["completed_at"])
    deadline = datetime.fromisoformat(current["rollback_deadline"])
    if completed.utcoffset() != timedelta(0) or deadline.utcoffset() != timedelta(0):
        raise ValueError("发行时间必须为 UTC")
    if not completed <= now < deadline:
        raise ValueError("当前不在已确认回退窗口内")


def validate_targets(release: dict, targets: dict) -> None:
    if not isinstance(targets, dict) or not targets:
        raise ValueError("缺少实际主机/服务范围")
    for host, services in targets.items():
        if not isinstance(host, str) or not isinstance(services, list) or not services:
            raise ValueError("主机/服务范围不合法")
        if not all(
            isinstance(name, str) and name in release["images"] for name in services
        ):
            raise ValueError("服务未登记镜像")


def atomic(path: Path, value: dict) -> None:
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".deployment-")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def begin(
    path: Path,
    release: dict,
    manifest: bytes,
    targets: dict,
    now: datetime,
    recovery_operation: str | None = None,
    schema_release: str | None = None,
) -> None:
    validate_targets(release, targets)
    rollback_policy(release)
    latest = path.parent / "latest-attempt.json"
    prior = json.loads(latest.read_text(encoding="utf-8")) if latest.exists() else None
    if (
        prior
        and prior["status"] == "in_progress"
        and recovery_operation != prior["operation_id"]
    ):
        raise ValueError(
            "存在未完成发行；先核对现场，再明确 recovery_operation，不能换 ID 跳过"
        )
    if (
        prior
        and prior["status"] == "in_progress"
        and any(
            not set(services).issubset(targets.get(host, []))
            for host, services in prior["targets"].items()
        )
    ):
        raise ValueError("恢复范围不能遗漏前次可能修改的实例")
    baseline_file = path.parent / "latest-deployment.json"
    baseline = (
        json.loads(baseline_file.read_text(encoding="utf-8"))
        if baseline_file.exists()
        else None
    )
    if baseline and (
        baseline["project"] != release["project"]
        or baseline["environment"] != release["environment"]
    ):
        raise ValueError("成功基线项目环境不匹配")
    value = {
        "operation_id": path.stem.removesuffix("-attempt"),
        "status": "in_progress",
        "project": release["project"],
        "environment": release["environment"],
        "release": release["id"],
        "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        "schema_release": schema_release or release["id"],
        "targets": targets,
        "started_at": now.isoformat(),
        "recovery_deadline": (
            now + timedelta(hours=release["rollback_window_hours"])
        ).isoformat(),
        "compatible_previous_versions": release["compatible_previous_versions"],
        "baseline": baseline,
        "recovery_of": recovery_operation,
    }
    if schema_release and schema_release != release["id"]:
        schema_evidence = (
            prior if prior and prior["status"] == "in_progress" else baseline
        )
        if (
            not schema_evidence
            or schema_evidence.get("schema_release", schema_evidence["release"])
            != schema_release
        ):
            raise ValueError("schema 来源必须与记录的实际发行一致")
        value["compatible_previous_versions"] = schema_evidence[
            "compatible_previous_versions"
        ]
        value["recovery_deadline"] = schema_evidence.get(
            "recovery_deadline", schema_evidence.get("rollback_deadline")
        )
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
    atomic(latest, value)


def save(path: Path, value: dict) -> None:
    # operation_id 唯一且外层已有管理锁；拒绝覆盖历史证据。
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    latest = path.parent / "latest-deployment.json"
    atomic(latest, value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("begin", "record", "check"))
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--rollback-from")
    parser.add_argument("--targets", type=Path)
    parser.add_argument("--attempt", type=Path)
    parser.add_argument("--recovery-operation")
    parser.add_argument("--schema-release")
    args = parser.parse_args()
    try:
        manifest = args.release.read_bytes()
        release = yaml.safe_load(manifest)
        now = datetime.now(UTC)
        targets = (
            json.loads(args.targets.read_text(encoding="utf-8"))
            if args.targets
            else None
        )
        if targets is not None:
            validate_targets(release, targets)
        if args.action == "begin":
            begin(
                args.receipt,
                release,
                manifest,
                targets,
                now,
                args.recovery_operation,
                args.schema_release,
            )
        elif args.action == "record":
            if targets is None or not args.attempt:
                raise ValueError("成功记录必须带实际范围和部署尝试")
            attempt = json.loads(args.attempt.read_text(encoding="utf-8"))
            if (
                attempt["targets"] != targets
                or attempt["manifest_sha256"] != hashlib.sha256(manifest).hexdigest()
            ):
                raise ValueError("部署途中范围或清单发生变化")
            result = receipt(release, manifest, now, targets)
            result["schema_release"] = attempt["schema_release"]
            if attempt["schema_release"] != release["id"]:
                result["rollback_deadline"] = attempt["recovery_deadline"]
                result["contract_not_before"] = attempt["recovery_deadline"]
                result["compatible_previous_versions"] = attempt[
                    "compatible_previous_versions"
                ]
            result["deployments"] = (attempt.get("baseline") or {}).get(
                "deployments", {}
            )
            for host, services in targets.items():
                result["deployments"].setdefault(host, {}).update(
                    {
                        name: {
                            "image": release["images"][name],
                            "release": release["id"],
                        }
                        for name in services
                    }
                )
            save(args.receipt, result)
            attempt["status"] = "completed"
            atomic(args.attempt, attempt)
            atomic(args.attempt.parent / "latest-attempt.json", attempt)
        else:
            attempt = (
                json.loads(args.attempt.read_text(encoding="utf-8"))
                if args.attempt and args.attempt.exists()
                else None
            )
            check_rollback(
                release,
                json.loads(args.receipt.read_text(encoding="utf-8")),
                args.rollback_from,
                now,
                attempt,
                targets,
            )
        print("发行回退窗口记录/校验通过")
        return 0
    except (ValueError, KeyError, TypeError, OSError, OverflowError, yaml.YAMLError):
        print("发行回退窗口记录/校验失败；检查当前成功记录、期限与兼容声明")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
