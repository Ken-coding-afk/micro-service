package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import com.ken.platform.identity.service.oauth.UserTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration(proxyBeanMethods = false)
public class OidcClaimsConfiguration {
  @Bean
  OAuth2TokenCustomizer<JwtEncodingContext> idTokenClaims(
      ObjectProvider<UserTokenService> users, ObjectProvider<LoginStore> sessions) {
    return context -> {
      if (!context.getTokenType().getValue().equals("id_token")) return;
      var service = users.getIfAvailable();
      var store = sessions.getIfAvailable();
      if (service == null
          || store == null
          || !(context.getPrincipal().getPrincipal() instanceof Identity user))
        throw new OAuth2AuthenticationException("invalid_grant");
      var session = store.session(user.sessionId(), false);
      if (session == null || session.revokedAt() != null)
        throw new OAuth2AuthenticationException("invalid_grant");
      var claims = service.profileClaims(user, context.getAuthorizedScopes());
      claims.forEach((key, value) -> context.getClaims().claim(key, value));
      // 与官方 Date 约定一致，refresh 读取持久化 ID Token 的 auth_time 不发生类型漂移。
      context.getClaims().claim("auth_time", java.util.Date.from(session.createdAt()));
    };
  }
}
