package com.ken.platform.identity.service.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;

/** 用户授权用实时角色投影，机器授权继续使用既有合同；不会把任意 token 当用户解析。 */
public final class UserIntrospectionProvider implements AuthenticationProvider {
  private final AuthenticationProvider machines;
  private final UserTokenService users;

  public UserIntrospectionProvider(AuthenticationProvider machines, UserTokenService users) {
    this.machines = machines;
    this.users = users;
  }

  @Override
  public boolean supports(Class<?> type) {
    return machines.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication input) {
    var request = (OAuth2TokenIntrospectionAuthenticationToken) input;
    try {
      if (!users.isUserToken(request.getToken())) return machines.authenticate(input);
      if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken caller))
        throw new OAuth2AuthenticationException("invalid_client");
      return new OAuth2TokenIntrospectionAuthenticationToken(
          request.getToken(), caller, users.introspect(request.getToken(), caller));
    } catch (OAuth2AuthenticationException known) {
      throw known;
    } catch (RuntimeException unavailable) {
      throw new OAuth2AuthenticationException("temporarily_unavailable");
    }
  }
}
