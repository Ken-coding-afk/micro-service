package com.ken.platform.foundation.security;

import com.ken.platform.foundation.http.CallContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** 普通资源 API 错误合同，不返回原认证异常或提供方正文。 */
public final class ResourceErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    boolean unavailable = failure instanceof AuthenticationServiceException;
    write(
        response,
        unavailable ? 503 : 401,
        unavailable ? "IDENTITY_UNAVAILABLE" : "INVALID_CREDENTIALS");
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.access.AccessDeniedException denied)
      throws IOException {
    write(response, 403, "FORBIDDEN");
  }

  public static void write(HttpServletResponse response, int status, String code)
      throws IOException {
    if (response.isCommitted()) return;
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    if (status == 401) response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    if (status == 403)
      response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
    var context = CallContexts.current();
    String request = context == null ? UUID.randomUUID().toString() : context.requestId();
    response.setHeader("X-Request-Id", request);
    var body = new LinkedHashMap<String, Object>();
    body.put("code", code);
    body.put("message", status == 503 ? "身份内省暂不可用" : status == 401 ? "访问令牌无效" : "无权访问");
    body.put("data", null);
    body.put("request_id", request);
    response.getOutputStream().write(JsonMapper.builder().build().writeValueAsBytes(body));
  }
}
