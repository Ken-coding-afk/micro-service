"""本地 Swagger 的标准 OAuth2 PKCE 配置；不改变 API 认证。"""

import os
import re

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi


def install_openapi(app: FastAPI, service: str, scopes: dict[str, str]) -> None:
    if os.environ.get("APP_ENV") != "local":
        return
    origin = os.environ["PUBLIC_ORIGIN"]
    tenant = os.environ.get("SWAGGER_TENANT_CODE", "")
    if tenant and not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", tenant):
        raise ValueError("Swagger 租户编码不合法")
    authorization = origin + "/oauth2/authorize" + ("?tenant_code=" + tenant if tenant else "")
    app.swagger_ui_init_oauth = {
        "clientId": service + "-swagger",
        "usePkceWithAuthorizationCodeGrant": True,
    }

    def schema():
        if app.openapi_schema is None:
            value = get_openapi(title=service, version="0.1.0", routes=app.routes)
            value.setdefault("components", {})["securitySchemes"] = {
                "oauth2": {
                    "type": "oauth2",
                    "flows": {
                        "authorizationCode": {
                            "authorizationUrl": authorization,
                            "tokenUrl": origin + "/oauth2/token",
                            "scopes": scopes,
                        }
                    },
                },
            }
            value["security"] = [{"oauth2": []}]
            app.openapi_schema = value
        return app.openapi_schema

    # FastAPI 的自定义 OpenAPI 扩展点是实例方法替换；保留延迟生成以包含后续路由。
    app.openapi = schema  # type: ignore[method-assign]
