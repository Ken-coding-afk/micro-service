package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 标准表单失败只展示稳定消息，不把原始异常或用户输入带入页面/审计。 */
public final class LoginFailures implements AuthenticationFailureHandler {
  private final SecurityAuditRepository audits;
  private final TransactionTemplate events;

  public LoginFailures(SecurityAuditRepository audits, PlatformTransactionManager manager) {
    this.audits = audits;
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException, jakarta.servlet.ServletException {
    String code =
        failure instanceof LoginAuthentication.Failure known ? known.code() : "INVALID_CREDENTIALS";
    int status = failure instanceof LoginAuthentication.Failure known ? known.status() : 401;
    long retry = failure instanceof LoginAuthentication.Failure known ? known.retry() : 0;
    if (!(failure instanceof LoginAuthentication.Failure known) || !known.audited()) {
      if (!record(code)) {
        code = "SECURITY_AUDIT_UNAVAILABLE";
        status = 503;
      }
    }
    SecurityContextHolder.clearContext();
    new HttpSessionSecurityContextRepository()
        .saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    request.setAttribute("loginError", code);
    request.setAttribute("loginStatus", status);
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    if (retry > 0) response.setHeader("Retry-After", Long.toString(retry));
    request.getRequestDispatcher("/identity/login").forward(request, response);
  }

  public boolean record(String code) {
    return recordAction(code, "identity.password.login");
  }

  public boolean recordExternal(String code) {
    return recordAction(code, "identity.external.login");
  }

  private boolean recordAction(String code, String action) {
    String safe =
        code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "INVALID_CREDENTIALS";
    String request =
        CallContexts.current() == null
            ? UUID.randomUUID().toString()
            : CallContexts.current().requestId();
    try {
      events.execute(
          status -> {
            audits.failure(
                null,
                "ANONYMOUS",
                null,
                action,
                null,
                request,
                tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .writeValueAsString(Map.of("error_code", safe)));
            return null;
          });
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public boolean recordInitial(
      com.ken.platform.identity.service.login.PasswordLoginService.Identity owner, String code) {
    String request =
        CallContexts.current() == null
            ? UUID.randomUUID().toString()
            : CallContexts.current().requestId();
    try {
      events.execute(
          status -> {
            audits.failure(
                owner.user(),
                owner.realm().name(),
                owner.tenant(),
                "identity.password.initial",
                owner.sessionId(),
                request,
                tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .writeValueAsString(Map.of("error_code", code)));
            return null;
          });
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
