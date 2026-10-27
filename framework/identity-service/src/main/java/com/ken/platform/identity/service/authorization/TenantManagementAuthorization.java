package com.ken.platform.identity.service.authorization;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

/** 管理请求共用可信主体解析；数据库授权须在调用者持有固定顺序锁后重查。 */
@Service
public final class TenantManagementAuthorization {
  private final UserManagementMapper mapper;

  public TenantManagementAuthorization(UserManagementMapper mapper) {
    this.mapper = mapper;
  }

  public UUID actorFor(String operation) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var context = CallContexts.current();
    if (authentication == null
        || !authentication.isAuthenticated()
        || context == null
        || context.tenantId() == null
        || !(authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal)
        || !"user".equals(principal.getAttribute("principal_type"))
        || !context.tenantId().toString().equals(principal.getAttribute("tenant_id"))
        || !authentication.getName().equals(context.principalId())) {
      throw new PlatformException("INVALID_CREDENTIALS", "缺少有效租户用户身份", 401);
    }
    if (authentication.getAuthorities().stream()
        .noneMatch(authority -> authority.getAuthority().equals("PERMISSION_" + operation))) {
      throw new PlatformException("FORBIDDEN", "当前应用无该管理授权", 403);
    }
    try {
      return UUID.fromString(context.principalId());
    } catch (IllegalArgumentException invalid) {
      throw new PlatformException("INVALID_CREDENTIALS", "主体标识无效", 401);
    }
  }

  public void requireProtectedAdministrator(
      UUID tenant, UUID actor, String operation, List<UserManagementMapper.UserRow> lockedUsers) {
    if (!isProtectedAdministrator(tenant, actor, operation, lockedUsers)) {
      throw new PlatformException("MANAGEMENT_NOT_ALLOWED", "当前主体无该管理能力", 403);
    }
  }

  public boolean isProtectedAdministrator(
      UUID tenant, UUID actor, String operation, List<UserManagementMapper.UserRow> lockedUsers) {
    var acting =
        lockedUsers.stream()
            .filter(user -> user.id().equals(actor))
            .findFirst()
            .orElseThrow(() -> new PlatformException("INVALID_CREDENTIALS", "当前身份不可用", 401));
    if (!acting.status().equals("ACTIVE") || acting.mustChangePassword()) {
      throw new PlatformException("INVALID_CREDENTIALS", "当前身份不可用", 401);
    }
    return mapper.protectedAdminCan(tenant, actor, operation);
  }
}
