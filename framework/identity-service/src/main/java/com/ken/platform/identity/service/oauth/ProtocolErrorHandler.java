package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 标准 OAuth JSON，不套业务 envelope；解析失败独立审计，不回显原始错误/参数。 */
public final class ProtocolErrorHandler implements AuthenticationFailureHandler {
  private static final Set<String> UNAVAILABLE_ERRORS =
      Set.of("server_error", "temporarily_unavailable");
  private final SecurityAuditRepository audits;
  private final TransactionTemplate events;

  public ProtocolErrorHandler(SecurityAuditRepository audits, PlatformTransactionManager manager) {
    this.audits = audits;
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    String code =
        failure instanceof OAuth2AuthenticationException known
            ? known.getError().getErrorCode()
            : "server_error";
    if (!code.matches("[a-z_]{1,64}")) code = "server_error";
    if (!(failure instanceof AuditedProtocolFailure)) {
      String errorCode = code;
      var context = CallContexts.current();
      String requestId = context == null ? UUID.randomUUID().toString() : context.requestId();
      try {
        events.execute(
            status -> {
              audits.protocolEvent(
                  "identity.oauth.protocol", "FAILURE", null, errorCode, requestId);
              return null;
            });
      } catch (RuntimeException unavailable) {
        code = "temporarily_unavailable";
      }
    }
    response.setStatus(
        UNAVAILABLE_ERRORS.contains(code) ? 503 : "invalid_client".equals(code) ? 401 : 400);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    if ("invalid_client".equals(code))
      response.setHeader("WWW-Authenticate", "Basic realm=\"oauth2\"");
    response
        .getWriter()
        .write(JsonMapper.builder().build().writeValueAsString(Map.of("error", code)));
  }
}
