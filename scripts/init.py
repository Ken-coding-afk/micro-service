"""初始化当前登记服务集的本地基础设施、技术身份和运行绑定。"""

import argparse
import subprocess

from common import FRAMEWORK
from local import initialize


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", choices=("local",), required=True)
    parser.add_argument("--services", default="all")
    parser.add_argument("--with-demo", action="store_true")
    parser.add_argument("--dev-host")
    try:
        initialize(FRAMEWORK, parser.parse_args())
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"初始化停止：{type(error).__name__}：{error}；保留已有数据和秘密，修正原因后重跑。")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
