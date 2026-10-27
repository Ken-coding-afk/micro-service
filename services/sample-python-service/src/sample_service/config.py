"""本服务拥有的设置；导入模块不读取环境或秘密。"""

import os
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import URL


class BusinessSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    max_page_size: int = Field(default=100, ge=20, le=100)


def database_url() -> URL:
    password = Path(os.environ["DB_PASSWORD_SECRET_FILE"]).read_text(encoding="utf8").strip()
    return URL.create(
        "postgresql+psycopg",
        username=os.environ["DB_USER"],
        password=password,
        host=os.environ["PG_HOST"],
        port=int(os.environ["PG_PORT"]),
        database=os.environ["DB_NAME"],
        query={"sslmode": "disable" if os.environ["APP_ENV"] == "local" else "verify-full"},
    )
