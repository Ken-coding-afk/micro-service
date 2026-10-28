package com.ken.platform.identity.controller.support;

import com.ken.platform.foundation.http.CallContexts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** 管理响应不缓存；与认证错误使用相同 request_id，不返回实现异常或秘密。 */
public final class IdentityApiResponses {
  private IdentityApiResponses() {}

  public static ResponseEntity<Map<String, Object>> ok(Object data) {
    return response(200, "OK", "success", data);
  }

  public static ResponseEntity<Map<String, Object>> response(
      int status, String code, String message, Object data) {
    var context = CallContexts.current();
    String request = context == null ? UUID.randomUUID().toString() : context.requestId();
    var body = new LinkedHashMap<String, Object>();
    body.put("code", code);
    body.put("message", message);
    body.put("data", data);
    body.put("request_id", request);
    var response =
        ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .header("X-Request-Id", request);
    if (status == 401) response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    if (status == 403) response.header("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
    return response.body(body);
  }
}
