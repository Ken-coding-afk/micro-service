"""验证固定构建来源与仓库镜像摘要，生成源码无关的部署包。"""

import argparse
import json
from pathlib import Path

from build import bundle, source_identity
from catalog import project_config
from common import FRAMEWORK


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-record", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--web-root", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("输出目录已存在")
    record = json.loads(args.build_record.read_text(encoding="utf-8"))
    args.release = record["release"]
    config = project_config(FRAMEWORK, record["services"])
    identity = source_identity(FRAMEWORK, strict=True, requested=record["services"])
    bundle(FRAMEWORK, args, config, identity)


if __name__ == "__main__":
    main()
