package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 标准授权错误仅返回到官方已验证回调；转换器失败独立审计，依赖失败明确 503。 */
public final class AuthorizationErrorHandler implements AuthenticationFailureHandler {
  private final SecurityAuditRepository audits;
  private final TransactionTemplate events;
  private final ProtocolErrorHandler json;

  public AuthorizationErrorHandler(
      SecurityAuditRepository audits, PlatformTransactionManager manager) {
    this.audits = audits;
    json = new ProtocolErrorHandler(audits, manager);
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
      String safe = code;
      String requestId =
          CallContexts.current() == null
              ? UUID.randomUUID().toString()
              : CallContexts.current().requestId();
      try {
        events.execute(
            status -> {
              audits.protocolEvent("identity.oauth.protocol", "FAILURE", null, safe, requestId);
              return null;
            });
      } catch (RuntimeException unavailable) {
        code = "temporarily_unavailable";
      }
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    if (!Set.of("server_error", "temporarily_unavailable").contains(code)
        && failure instanceof OAuth2AuthorizationCodeRequestAuthenticationException known
        && known.getAuthorizationCodeRequestAuthentication() != null) {
      var authorization = known.getAuthorizationCodeRequestAuthentication();
      if (authorization.getRedirectUri() != null && !authorization.getRedirectUri().isBlank()) {
        var uri =
            UriComponentsBuilder.fromUriString(authorization.getRedirectUri())
                .queryParam("error", code);
        if (authorization.getState() != null)
          uri.queryParam(
              "state", UriUtils.encode(authorization.getState(), StandardCharsets.UTF_8));
        response.sendRedirect(uri.build(true).toUriString());
        return;
      }
    }
    json.onAuthenticationFailure(request, response, new ProtocolProvider.AuditedFailure(code));
  }
}
