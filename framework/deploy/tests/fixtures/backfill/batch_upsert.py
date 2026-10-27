#!/usr/bin/env python3
"""可续跑回填示例(设计 10 §2 第 4 条:回填是独立可续跑任务)。

关键合同:
- 每批一个短事务:数据 upsert 与游标推进在同一事务内提交(断点与数据同事务);
- 幂等写入:``INSERT ... ON CONFLICT (id) DO UPDATE``,值由 id 确定性推导,
  任意批次重复执行结果一致;
- 游标持久化在目标库的进度表 ``t11a_backfill_progress`` 中,进程被杀后重跑
  从已提交游标继续,不重头、不跳号;
- 使用维护账号(migrator)执行,业务 runtime 账号不参与回填(设计 10 §1)。

用法(T-11A 测试)::

    python batch_upsert.py --host 127.0.0.1 --port 15532 --db t11a_fw_fill \
        --user t11a_fw_migrator --password-file <file> \
        --total 500 --batch 20 --delay 0.08
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import psycopg

PROGRESS_TABLE = "t11a_backfill_progress"
DATA_TABLE = "t11a_backfill"


def build_value(row_id: int) -> str:
    """由 id 确定性推导回填值,保证幂等重跑结果一致。"""
    return f"v{row_id:06d}"


def ensure_progress_table(conn: psycopg.Connection) -> None:
    """进度表不存在则创建(单行游标)。"""
    with conn.transaction():
        conn.execute(
            f"""
            CREATE TABLE IF NOT EXISTS {PROGRESS_TABLE} (
                id         INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                last_id    BIGINT NOT NULL DEFAULT 0,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute(f"INSERT INTO {PROGRESS_TABLE} (id, last_id) VALUES (1, 0) ON CONFLICT (id) DO NOTHING")


def read_cursor(conn: psycopg.Connection) -> int:
    return int(conn.execute(f"SELECT last_id FROM {PROGRESS_TABLE} WHERE id = 1").fetchone()[0])


def run_batch(conn: psycopg.Connection, start: int, end: int) -> None:
    """一批一个短事务:upsert 数据 + 推进游标,同事务提交。"""
    rows = [(i, build_value(i)) for i in range(start, end + 1)]
    with conn.transaction():
        psycopg.ClientCursor(conn).executemany(
            f"""
            INSERT INTO {DATA_TABLE} (id, value) VALUES (%s, %s)
            ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
            """,
            rows,
        )
        conn.execute(
            f"UPDATE {PROGRESS_TABLE} SET last_id = %s, updated_at = now() WHERE id = 1",
            (end,),
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="T-11A 可续跑回填示例(幂等 upsert + 同事务游标)")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--user", required=True, help="维护账号(migrator),不是业务 runtime")
    parser.add_argument("--password-file", required=True)
    parser.add_argument("--total", type=int, required=True, help="回填目标总行数(id 1..total)")
    parser.add_argument("--batch", type=int, default=20, help="每批行数(短事务)")
    parser.add_argument("--delay", type=float, default=0.05, help="批间延迟秒数(便于测试中断)")
    args = parser.parse_args(argv)

    password = Path(args.password_file).read_text(encoding="utf-8").strip()
    # autocommit=True:使每个 conn.transaction() 块成为真正的独立短事务
    # (BEGIN...COMMIT);否则 psycopg 在隐式事务内退化为 savepoint,
    # 批次提交对其他连接不可见,违反"断点与数据同事务"合同。
    with psycopg.connect(
        host=args.host, port=args.port, dbname=args.db, user=args.user,
        password=password, autocommit=True,
    ) as conn:
        ensure_progress_table(conn)
        cursor = read_cursor(conn)
        print(f"[回填] 起始游标 last_id={cursor}", flush=True)
        if cursor >= args.total:
            print(f"[回填] 已完成,无需续跑(游标 {cursor} >= 目标 {args.total})", flush=True)
            return 0
        processed = 0
        while cursor < args.total:
            start = cursor + 1
            end = min(start + args.batch - 1, args.total)
            run_batch(conn, start, end)
            cursor = end
            processed += end - start + 1
            print(f"[回填] 批次完成 {start}..{end},游标={cursor}", flush=True)
            if cursor < args.total and args.delay > 0:
                time.sleep(args.delay)
        print(f"[回填] 全部完成:共处理 {processed} 行,游标={cursor}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
