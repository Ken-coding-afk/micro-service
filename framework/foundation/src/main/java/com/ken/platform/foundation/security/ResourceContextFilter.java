package com.ken.platform.foundation.security;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅置于已认证 Bearer 过滤器之后；词法作用域在异常和异步交接时自动关闭。 */
public final class ResourceContextFilter extends OncePerRequestFilter {
  static final String PRINCIPAL = ResourceContextFilter.class.getName() + ".principal";
  public static final String CONTEXT = ResourceContextFilter.class.getName() + ".context";
  private final Clock clock;
  private final RuntimeParameters parameters;

  public ResourceContextFilter(Clock clock, RuntimeParameters parameters) {
    this.clock = clock;
    this.parameters = parameters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof BearerTokenAuthentication bearer)
        || !bearer.isAuthenticated()
        || !(bearer.getPrincipal() instanceof CurrentPrincipal principal)) {
      chain.doFilter(request, response);
      return;
    }
    var parent = CallContexts.current();
    var context =
        new CallContext(
            parent == null ? request.getHeader("X-Request-Id") : parent.requestId(),
            principal.tenantId(),
            principal.subject(),
            bearer.getToken().getTokenValue(),
            parent == null ? request.getHeader("traceparent") : parent.traceparent(),
            parent == null
                ? clock.instant().plusMillis((long) (parameters.responseBudgetSeconds() * 1000))
                : parent.deadline());
    request.setAttribute(PRINCIPAL, principal);
    request.setAttribute(CONTEXT, context);
    CallContexts.Scope scope;
    try {
      scope = CallContexts.bind(context);
    } catch (IllegalArgumentException conflict) {
      ResourceErrors.write(response, 403, "FORBIDDEN");
      return;
    }
    try (scope) {
      chain.doFilter(request, response);
    }
  }
}
