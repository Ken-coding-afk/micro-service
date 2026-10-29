package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.security.Principal;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/** 仅为当前会话授权流程追加仍有效的准确回调；不改变标准协议及 CSRF 校验。 */
final class BrowserContentSecurityPolicy implements HeaderWriter {
  private static final String POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
  private final Environment environment;
  private final ClientStore clients;
  private final OAuth2AuthorizationService authorizations;

  BrowserContentSecurityPolicy(
      Environment environment, ClientStore clients, OAuth2AuthorizationService authorizations) {
    this.environment = environment;
    this.clients = clients;
    this.authorizations = authorizations;
  }

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    String redirect = null;
    try {
      if (response.getStatus() < 400) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Target target =
            path.equals("/identity/consent")
                ? consentTarget(request)
                : Set.of(
                            "/identity/login",
                            "/identity/password/initial",
                            "/identity/password/reset")
                        .contains(path)
                    ? savedTarget(request, response)
                    : null;
        if (target != null) redirect = registeredSource(target);
      }
    } catch (org.springframework.dao.DataAccessException unavailable) {
      // 依赖故障时保留同源限制，不遮蔽原有的不可用响应。
    }
    response.setHeader(
        "Content-Security-Policy", POLICY + (redirect == null ? "" : " " + redirect));
  }

  private record Target(String clientId, String redirectUri, String registeredClientId) {}

  private static String single(String[] values) {
    return values != null && values.length == 1 && !values[0].isBlank() ? values[0] : null;
  }

  private Target savedTarget(HttpServletRequest request, HttpServletResponse response) {
    var saved = new HttpSessionRequestCache().getRequest(request, response);
    if (!(saved instanceof DefaultSavedRequest original)
        || !original.getMethod().equals("GET")
        || !original.getRequestURI().equals(request.getContextPath() + "/oauth2/authorize"))
      return null;
    String client = single(original.getParameterValues("client_id"));
    String redirect = single(original.getParameterValues("redirect_uri"));
    return client == null || redirect == null ? null : new Target(client, redirect, null);
  }

  private Target consentTarget(HttpServletRequest request) {
    String state = single(request.getParameterValues("state"));
    String clientId = single(request.getParameterValues("client_id"));
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (state == null
        || state.length() > 1024
        || clientId == null
        || authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof Identity identity)) return null;
    var pending = authorizations.findByToken(state, new OAuth2TokenType("state"));
    if (pending == null
        || !state.equals(pending.getAttribute("state"))
        || !identity.getName().equals(pending.getPrincipalName())
        || !(pending.getAttribute(Principal.class.getName()) instanceof Authentication original)
        || !identity.equals(original.getPrincipal())
        || !(pending.getAttribute(OAuth2AuthorizationRequest.class.getName())
            instanceof OAuth2AuthorizationRequest authorization)
        || !clientId.equals(authorization.getClientId())) return null;
    return new Target(clientId, authorization.getRedirectUri(), pending.getRegisteredClientId());
  }

  private String registeredSource(Target target) {
    var current = clients.byClientId(target.clientId());
    if (current == null
        || current.policy().status() != Status.ACTIVE
        || current.policy().principalType() == PrincipalType.SERVICE
        || target.registeredClientId() != null
            && !target.registeredClientId().equals(current.client().getId())
        || target.redirectUri() == null
        || !current.client().getRedirectUris().contains(target.redirectUri())) return null;
    try {
      URI uri = URI.create(target.redirectUri());
      boolean local =
          "local".equals(environment.getProperty("APP_ENV"))
              && "http".equals(uri.getScheme())
              && Set.of("localhost", "127.0.0.1", "[::1]")
                  .contains(uri.getHost() == null ? "" : uri.getHost());
      if (uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || !("https".equals(uri.getScheme()) || local)
          || target.redirectUri().contains("*")
          || !uri.toASCIIString().equals(target.redirectUri())) return null;
      // CSP source 不包含 query；完整 redirect_uri 仍由 SAS 精确匹配。分号不能成为新指令。
      return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath().replace(";", "%3B");
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }
}
