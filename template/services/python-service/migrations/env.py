"""迁移只依赖 Alembic/SQLAlchemy；连接由发布入口注入，不加载应用或 SDK。"""

import os

from alembic import context
from sqlalchemy import create_engine, pool

with create_engine(
    os.environ["DATABASE_URL"], poolclass=pool.NullPool
).connect() as connection:
    context.configure(connection=connection)
    with context.begin_transaction():
        context.run_migrations()
