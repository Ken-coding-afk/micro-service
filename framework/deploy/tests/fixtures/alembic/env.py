"""T-11A 样例 Alembic 环境(最小 env.py)。

连接串来自环境变量 DATABASE_URL(由 migrate.py 构造,已包含
``options=-c role=<owner> -c lock_timeout=5s -c statement_timeout=5min``),
本文件不保存任何环境值或秘密(设计 02/03)。
"""

from __future__ import annotations

import os

from alembic import context
from sqlalchemy import engine_from_config, pool

config = context.config

# 优先使用注入的 DATABASE_URL;缺省时回退 alembic.ini 的 sqlalchemy.url
# (样例环境为空,即显式失败,避免误连)。configparser 会对 % 做插值,
# URL 中的百分号编码(options=-c%20role%3D...)需先转义。
injected_url = os.environ.get("DATABASE_URL", "").strip()
if injected_url:
    config.set_main_option("sqlalchemy.url", injected_url.replace("%", "%%"))

target_metadata = None  # 样例不使用 autogenerate(设计 10:自动生成须人工审查)


def run_migrations_offline() -> None:
    """离线模式仅生成 SQL;样例流程不使用。"""
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """在线模式:经 engine 连接执行迁移(role/超时已在连接参数中设置)。"""
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
