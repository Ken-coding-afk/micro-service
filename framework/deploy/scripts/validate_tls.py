"""使用系统 OpenSSL 校验证书域名、有效期及私钥匹配；不打印私钥或修改证书。"""

import argparse
import hashlib
import re
import subprocess
from pathlib import Path


def openssl(*args: str, data: bytes | None = None) -> bytes:
    result = subprocess.run(
        ["openssl", *args], input=data, capture_output=True, timeout=10, check=False
    )
    if result.returncode:
        raise ValueError("OpenSSL 校验失败")
    return result.stdout


def validate(root: Path, host: str) -> str:
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host):
        raise ValueError("证书域名不合法")
    cert, key = root / "current/fullchain.pem", root / "current/privkey.pem"
    if not cert.resolve().is_relative_to(
        root.resolve()
    ) or not key.resolve().is_relative_to(root.resolve()):
        raise ValueError("current 必须引用证书父目录内版本")
    openssl("x509", "-in", str(cert), "-noout", "-checkhost", host)
    openssl("x509", "-in", str(cert), "-noout", "-checkend", str(7 * 86400))
    public = openssl("x509", "-in", str(cert), "-pubkey", "-noout")
    encoded = openssl("pkey", "-pubin", "-outform", "DER", data=public)
    actual = openssl(
        "pkey", "-in", str(key), "-pubout", "-outform", "DER", "-passin", "pass:"
    )
    if encoded != actual:
        raise ValueError("证书与私钥不匹配")
    # 完整信任链和对外域名由发布后的 validate_certs 烟测验证。
    return hashlib.sha256(cert.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate-dir", required=True, type=Path)
    parser.add_argument("--host", required=True)
    args = parser.parse_args()
    try:
        print(
            "证书域名/期限/私钥匹配通过；证书摘要="
            + validate(args.certificate_dir.resolve(), args.host)
        )
        return 0
    except (ValueError, OSError, subprocess.SubprocessError):
        print("TLS 校验失败，未切换配置；检查 current、域名、期限和私钥")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
