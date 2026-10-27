package com.ken.platform.identity.service.oauth;

import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.SessionCleanup;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

/** 官方验证 id_token_hint/精确退出回调后，将同一 SSO 的 DB 撤销与缓存清理组合。 */
public final class OidcSessionLogoutProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final ProtocolAuthorizations authorizations;
  private final PasswordLoginService logins;
  private final SessionCleanup cleanup;

  public OidcSessionLogoutProvider(
      AuthenticationProvider delegate,
      ProtocolAuthorizations authorizations,
      PasswordLoginService logins,
      SessionCleanup cleanup) {
    this.delegate = delegate;
    this.authorizations = authorizations;
    this.logins = logins;
    this.cleanup = cleanup;
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication input) {
    var request = (OidcLogoutAuthenticationToken) input;
    if (request.getIdTokenHint() == null || request.getIdTokenHint().isBlank())
      throw new OAuth2AuthenticationException("invalid_request");
    if (!(request.getPrincipal() instanceof Authentication principal)
        || !(principal.getPrincipal() instanceof PasswordLoginService.Identity user))
      throw new OAuth2AuthenticationException("invalid_token");
    var observed =
        authorizations.observeByToken(request.getIdTokenHint(), new OAuth2TokenType("id_token"));
    if (observed == null
        || !user.sessionId()
            .toString()
            .equals(observed.getAttribute(ProtocolAuthorizations.SESSION)))
      throw new OAuth2AuthenticationException("invalid_token");
    Authentication result = delegate.authenticate(input);
    logins.revokeCurrentSession();
    cleanup.clean(user.sessionId());
    return result;
  }
}
