"""Linux Certbot deploy hook / provided 证书更新入口：校验、切换、reload，失败恢复旧引用。"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import uuid
from pathlib import Path

from validate_tls import validate


def activate(root: Path, cert: Path, key: Path, host: str, reload_nginx) -> str:
    root = root.resolve()
    if root == Path(root.anchor):
        raise ValueError("证书目录不能为文件系统根目录")
    current = root / "current"
    if current.exists() and not current.is_symlink():
        raise ValueError("current 必须是版本目录引用，不能覆盖现有目录")
    if current.is_symlink() and not current.resolve().is_relative_to(root):
        raise ValueError("旧证书引用越界")
    digest = hashlib.sha256(cert.read_bytes()).hexdigest()
    version = root / "versions" / digest
    if not version.resolve().is_relative_to(root):
        raise ValueError("证书版本目录越界")
    # 独立候选目录先完成全部检查，不提前更换正在使用的证书。
    stage = root / ("candidate-" + uuid.uuid4().hex)
    (stage / "current").mkdir(parents=True, mode=0o700)
    shutil.copyfile(cert, stage / "current/fullchain.pem")
    shutil.copyfile(key, stage / "current/privkey.pem")
    (stage / "current/privkey.pem").chmod(0o600)
    validate(stage, host)
    version.parent.mkdir(parents=True, exist_ok=True)
    if not version.exists():
        os.replace(stage / "current", version)
        stage.rmdir()
    else:
        if (version / "privkey.pem").read_bytes() != key.read_bytes() or (
            version / "fullchain.pem"
        ).read_bytes() != cert.read_bytes():
            raise ValueError("已有证书版本内容不一致")
        # 仅删除本次创建的两个已知文件，不清理历史版本或任意目录。
        (stage / "current/fullchain.pem").unlink()
        (stage / "current/privkey.pem").unlink()
        (stage / "current").rmdir()
        stage.rmdir()
    previous = os.readlink(current) if current.is_symlink() else None
    pending = root / ("current-" + uuid.uuid4().hex)
    pending.symlink_to(Path("versions") / digest, target_is_directory=True)
    os.replace(pending, current)
    try:
        reload_nginx()
    except (OSError, ValueError, subprocess.SubprocessError):
        if previous is not None:
            pending.symlink_to(previous, target_is_directory=True)
            os.replace(pending, current)
            reload_nginx()
        else:
            current.unlink()
        raise
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--compose-env", required=True, type=Path)
    parser.add_argument("--compose-file", action="append", required=True)
    parser.add_argument("--certificate-dir", required=True, type=Path)
    parser.add_argument("--fullchain", required=True, type=Path)
    parser.add_argument("--privatekey", required=True, type=Path)
    parser.add_argument("--host", required=True)
    args = parser.parse_args()
    root = args.project_root.resolve()
    lock = root / ".local/operations/lock"
    try:
        if os.name != "posix":
            raise ValueError("证书加载入口仅用于 Linux 主机")
        if root == Path(root.anchor):
            raise ValueError("项目目录不能为文件系统根目录")
        lock.parent.mkdir(parents=True, exist_ok=True)
        lock.mkdir()  # 与发布共用目标主机锁；既有锁不抢占。
        (lock / "owner.json").write_text(
            json.dumps({"operation_id": "certificate-" + uuid.uuid4().hex}),
            encoding="utf-8",
        )
        command = [
            "docker",
            "compose",
            "--project-directory",
            str(root),
            "--env-file",
            str(args.compose_env),
        ]
        for name in args.compose_file:
            path = (root / name).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                raise ValueError("Compose 文件越界")
            command += ["-f", str(path)]

        def reload_nginx():
            for operation in (["nginx", "-t"], ["nginx", "-s", "reload"]):
                result = subprocess.run(
                    command + ["exec", "-T", "nginx", *operation],
                    capture_output=True,
                    timeout=20,
                    check=False,
                )
                if result.returncode:
                    raise ValueError("Nginx 校验或 reload 失败")

        digest = activate(
            args.certificate_dir,
            args.fullchain,
            args.privatekey,
            args.host,
            reload_nginx,
        )
        (lock / "owner.json").unlink()
        lock.rmdir()
        print("证书已校验并 reload；请核对对外证书与信任链，摘要=" + digest)
        return 0
    except (ValueError, OSError, subprocess.SubprocessError):
        print("证书更新中止；保留锁及版本现场，先核对再恢复")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
