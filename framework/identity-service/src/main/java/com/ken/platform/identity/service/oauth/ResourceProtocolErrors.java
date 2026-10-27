package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** UserInfo 只给必要的 Bearer 错误，依赖/事件存储失败为 503，绝不输出 token 或异常正文。 */
public final class ResourceProtocolErrors
    implements AuthenticationEntryPoint, AuthenticationFailureHandler {
  private final SecurityAuditRepository audits;
  private final TransactionTemplate events;

  public ResourceProtocolErrors(
      SecurityAuditRepository audits, PlatformTransactionManager manager) {
    this.audits = audits;
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    onAuthenticationFailure(request, response, failure);
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    String code =
        failure instanceof OAuth2AuthenticationException known
            ? known.getError().getErrorCode()
            : "invalid_token";
    if (failure instanceof AuthenticationServiceException
        || Set.of("temporarily_unavailable", "server_error").contains(code))
      code = "temporarily_unavailable";
    else if (!code.equals("insufficient_scope")) code = "invalid_token";
    String error = code;
    String id =
        CallContexts.current() == null
            ? UUID.randomUUID().toString()
            : CallContexts.current().requestId();
    try {
      events.execute(
          status -> {
            audits.protocolEvent("identity.oauth.protocol", "FAILURE", null, error, id);
            return null;
          });
    } catch (RuntimeException unavailable) {
      code = "temporarily_unavailable";
    }
    response.setStatus(
        code.equals("temporarily_unavailable")
            ? 503
            : code.equals("insufficient_scope") ? 403 : 401);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("WWW-Authenticate", "Bearer error=\"" + code + "\"");
    response.setContentType("application/json");
    response
        .getWriter()
        .write(JsonMapper.builder().build().writeValueAsString(Map.of("error", code)));
  }
}
