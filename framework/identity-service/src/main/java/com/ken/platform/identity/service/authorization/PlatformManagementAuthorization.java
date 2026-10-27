package com.ken.platform.identity.service.authorization;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

@Service
public final class PlatformManagementAuthorization {
  private final AccountManagementMapper mapper;

  public PlatformManagementAuthorization(AccountManagementMapper mapper) {
    this.mapper = mapper;
  }

  public UUID actorFor(String operation) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var context = CallContexts.current();
    if (auth == null
        || !auth.isAuthenticated()
        || context == null
        || context.tenantId() != null
        || !(auth.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal)
        || !"platform".equals(principal.getAttribute("principal_type"))
        || principal.getAttribute("tenant_id") != null
        || !auth.getName().equals(context.principalId())) {
      throw invalid();
    }
    if (auth.getAuthorities().stream()
        .noneMatch(authority -> authority.getAuthority().equals("PERMISSION_" + operation))) {
      throw new PlatformException("FORBIDDEN", "当前应用无该平台管理授权", 403);
    }
    try {
      return UUID.fromString(context.principalId());
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  public void requireActive(UUID actor) {
    var row = mapper.lockPlatformUser(actor);
    if (row == null || !row.status().equals("ACTIVE") || row.mustChangePassword()) {
      throw invalid();
    }
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "需要有效的平台管理员身份", 401);
  }
}
