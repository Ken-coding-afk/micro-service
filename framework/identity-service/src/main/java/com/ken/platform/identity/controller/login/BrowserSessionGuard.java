package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.SessionCleanup;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** SSO 不能仅凭 Redis 中的旧 Authentication 继续；浏览器请求还需匹配数据库绑定并重查状态。 */
public final class BrowserSessionGuard {
  private final PasswordLoginService service;
  private final SessionCleanup cleanup;
  private final com.ken.platform.identity.dal.mapper.oauth.ClientStore clients;

  public BrowserSessionGuard(
      PasswordLoginService service,
      SessionCleanup cleanup,
      com.ken.platform.identity.dal.mapper.oauth.ClientStore clients) {
    this.service = service;
    this.cleanup = cleanup;
    this.clients = clients;
  }

  public Filter filter(boolean protocol) {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (protocol && !path.equals("/oauth2/authorize") && !path.equals("/connect/logout")
            || !protocol && (path.equals("/identity/login") || path.equals("/identity/logout"))) {
          chain.doFilter(request, response);
          return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (protocol
            && path.equals("/oauth2/authorize")
            && request.getMethod().equals("GET")
            && "login".equals(request.getParameter("prompt"))
            && managedBff(request)) {
          // 只要求显式重新认证，不把旧会话改成另一个主体，也不撤销其他 BFF 的授权。
          // 保存时移除一次性的 prompt，首次改密/重新登录后不会重复强制登录。
          new org.springframework.security.web.savedrequest.HttpSessionRequestCache()
              .saveRequest(withoutPrompt(request), response);
          response.setHeader("Cache-Control", "no-store");
          response.sendRedirect(loginLocation(request));
          return;
        }
        if (protocol
            && path.equals("/oauth2/authorize")
            && (auth == null || !(auth.getPrincipal() instanceof PasswordLoginService.Identity))) {
          response.setHeader("Cache-Control", "no-store");
          if (request.getMethod().equals("GET")) {
            new org.springframework.security.web.savedrequest.HttpSessionRequestCache()
                .saveRequest(request, response);
            response.sendRedirect(loginLocation(request));
          } else response.setStatus(401);
          return;
        }
        if (auth != null && auth.getPrincipal() instanceof PasswordLoginService.Identity identity) {
          try {
            var session = request.getSession(false);
            service.verifyBrowserBinding(session == null ? null : session.getId());
            if (path.equals("/identity/password/initial")) service.requireInitialSession();
            else service.requireBusinessSession();
          } catch (PlatformException rejected) {
            response.setHeader("Cache-Control", "no-store");
            if (rejected.code().equals("PASSWORD_CHANGE_REQUIRED")
                && request.getMethod().equals("GET")) {
              response.sendRedirect("/identity/password/initial");
              return;
            }
            if (rejected.status() == 401) {
              SecurityContextHolder.clearContext();
              cleanup.clean(identity.sessionId());
              var old = request.getSession(false);
              if (old != null) old.invalidate();
              if (request.getMethod().equals("GET")) {
                response.sendRedirect("/identity/login");
                return;
              }
            }
            response.setStatus(rejected.status());
            response.setContentType("text/html;charset=UTF-8");
            response
                .getWriter()
                .write(
                    "<!doctype html><html lang=zh-CN><meta charset=utf-8><title>请求未完成</title><p>当前登录状态无法完成该操作，请重新登录或稍后重试。</p></html>");
            return;
          }
        }
        chain.doFilter(request, response);
      }
    };
  }

  private boolean managedBff(HttpServletRequest request) {
    String id = request.getParameter("client_id");
    if (!java.util.Set.of("identity-admin-platform", "identity-admin-tenant", "web-bff")
        .contains(id == null ? "" : id)) return false;
    var current = clients.byClientId(id);
    return current != null
        && current.policy().status()
            == com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status.ACTIVE
        && !current.client().getClientSettings().isRequireAuthorizationConsent()
        && current.client().getClientSettings().isRequireProofKey()
        && !current
            .client()
            .getClientAuthenticationMethods()
            .contains(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
        && current.client().getRedirectUris().contains(request.getParameter("redirect_uri"));
  }

  private static String loginLocation(HttpServletRequest request) {
    String id = request.getParameter("client_id");
    if ("identity-admin-platform".equals(id)) return "/identity/login?realm=PLATFORM";
    String tenant = request.getParameter("tenant_code");
    if (java.util.Set.of("identity-admin-tenant", "web-bff").contains(id == null ? "" : id)
        && tenant != null
        && tenant.matches("[a-z][a-z0-9-]{0,63}"))
      return "/identity/login?realm=TENANT&tenant_code=" + tenant;
    return "/identity/login";
  }

  private static HttpServletRequest withoutPrompt(HttpServletRequest request) {
    var parameters = new java.util.LinkedHashMap<>(request.getParameterMap());
    parameters.remove("prompt");
    String query =
        parameters.entrySet().stream()
            .flatMap(
                entry ->
                    java.util.Arrays.stream(entry.getValue())
                        .map(
                            value ->
                                java.net.URLEncoder.encode(
                                        entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)
                                    + "="
                                    + java.net.URLEncoder.encode(
                                        value, java.nio.charset.StandardCharsets.UTF_8)))
            .collect(java.util.stream.Collectors.joining("&"));
    return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
      @Override
      public String getQueryString() {
        return query;
      }

      @Override
      public java.util.Map<String, String[]> getParameterMap() {
        return java.util.Collections.unmodifiableMap(parameters);
      }

      @Override
      public String[] getParameterValues(String key) {
        return parameters.get(key);
      }

      @Override
      public String getParameter(String key) {
        String[] values = parameters.get(key);
        return values == null || values.length == 0 ? null : values[0];
      }
    };
  }
}
