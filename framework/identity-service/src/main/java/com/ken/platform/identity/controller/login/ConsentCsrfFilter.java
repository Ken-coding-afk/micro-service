package com.ken.platform.identity.controller.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** 授权服务器的协议豁免不适用于浏览器同意/退出 POST；与登录页共用会话 CSRF 仓库。 */
public final class ConsentCsrfFilter extends OncePerRequestFilter {
  private final CsrfFilter csrf = new CsrfFilter(new HttpSessionCsrfTokenRepository());

  public ConsentCsrfFilter(
      com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository audits,
      org.springframework.transaction.PlatformTransactionManager manager) {
    csrf.setBeanName("identityConsentCsrf");
    var events = new org.springframework.transaction.support.TransactionTemplate(manager);
    events.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
    csrf.setAccessDeniedHandler(
        (request, response, rejected) -> {
          String code = "invalid_request";
          int status = 403;
          var context = com.ken.platform.foundation.http.CallContexts.current();
          String id =
              context == null ? java.util.UUID.randomUUID().toString() : context.requestId();
          try {
            events.execute(
                transaction -> {
                  audits.protocolEvent(
                      "identity.oauth.protocol", "FAILURE", null, "invalid_request", id);
                  return null;
                });
          } catch (RuntimeException unavailable) {
            code = "temporarily_unavailable";
            status = 503;
          }
          response.setStatus(status);
          response.setContentType("application/json");
          response.setHeader("Cache-Control", "no-store");
          response
              .getWriter()
              .write(
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .writeValueAsString(java.util.Map.of("error", code)));
        });
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("POST")
        || !(request.getRequestURI().equals(request.getContextPath() + "/oauth2/authorize")
            || request.getRequestURI().equals(request.getContextPath() + "/connect/logout"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    csrf.doFilter(request, response, chain);
  }
}
