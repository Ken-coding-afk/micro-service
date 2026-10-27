package com.ken.platform.identity.service.security;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

/** 使用本地已验证登录绑定的认证时间；token iat、刷新及浏览器参数不能刷新该期限。 */
@Service
public final class RecentAuthentication {
  private final SecurityPolicies policies;
  private final Clock clock;

  public RecentAuthentication(SecurityPolicies policies, Clock clock) {
    this.policies = policies;
    this.clock = clock;
  }

  public void require(UUID tenant) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    Instant authenticated =
        authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
            ? principal.getAttribute(UserTokenService.AUTHENTICATED_AT)
            : null;
    Instant now = clock.instant();
    if (authenticated == null
        || now.isBefore(authenticated)
        || !now.isBefore(authenticated.plusSeconds(policies.current(tenant).recentAuthSeconds())))
      throw new PlatformException("RECENT_AUTHENTICATION_REQUIRED", "请重新认证后执行凭据或应用配置操作", 403);
  }
}
