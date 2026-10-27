package com.ken.platform.identity.config;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.ResourceErrors;
import com.ken.platform.identity.service.audit.ManagementAudits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 在认证/路由拒绝阶段也记录脱敏失败；审计不可用时失败关闭。 */
final class IdentityResourceErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ManagementAudits audits;
  private final ResourceErrors errors = new ResourceErrors();

  IdentityResourceErrors(ManagementAudits audits) {
    this.audits = audits;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    if (retired(request)) {
      if (record(response, "NOT_FOUND")) ResourceErrors.write(response, 404, "NOT_FOUND");
      return;
    }
    String code =
        failure instanceof AuthenticationServiceException
            ? "IDENTITY_UNAVAILABLE"
            : "INVALID_CREDENTIALS";
    if (record(response, code)) errors.commence(request, response, failure);
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.access.AccessDeniedException failure)
      throws IOException {
    if (retired(request)) {
      if (record(response, "NOT_FOUND")) ResourceErrors.write(response, 404, "NOT_FOUND");
      return;
    }
    if (record(response, "FORBIDDEN")) errors.handle(request, response, failure);
  }

  private static boolean retired(HttpServletRequest request) {
    return java.util.Set.of(
            "/api/v1/identity/management-grants", "/api/v1/identity/authorization-evaluations")
        .contains(request.getRequestURI());
  }

  private boolean record(HttpServletResponse response, String code) throws IOException {
    try {
      audits.attempt(
          ManagementAudits.Action.API_REQUEST,
          null,
          () -> {
            throw new PlatformException(code, "API 请求未获授权", 403);
          });
      throw new IllegalStateException("拒绝审计未抛出预期错误");
    } catch (PlatformException recorded) {
      if (!recorded.code().equals(code)) {
        ResourceErrors.write(response, 503, "SECURITY_AUDIT_UNAVAILABLE");
        return false;
      }
      return true;
    }
  }
}
