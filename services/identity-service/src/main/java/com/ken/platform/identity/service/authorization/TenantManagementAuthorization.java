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

  private final PlatformManagementAuthorization platform;
  private final com.ken.platform.identity.dal.support.IdentityTenantScope scope;
  private final org.springframework.transaction.support.TransactionTemplate control;
  private static final String TARGET = TenantManagementAuthorization.class.getName() + ".target";

  private record Target(UUID tenant, UUID actor) {}

  @org.springframework.beans.factory.annotation.Autowired
  public TenantManagementAuthorization(
      UserManagementMapper mapper,
      PlatformManagementAuthorization platform,
      com.ken.platform.identity.dal.support.IdentityTenantScope scope,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.platform = platform;
    this.scope = scope;
    this.control = new org.springframework.transaction.support.TransactionTemplate(manager);
    this.control.setTimeout(10);
  }

  public TenantManagementAuthorization(UserManagementMapper mapper) {
    this.platform = null;
    this.scope = null;
    this.control = null;
    this.mapper = mapper;
  }

  /** MVC 仅对显式平台管理映射调用；目标留在当前请求，不改写认证主体。 */
  public void selectTarget(jakarta.servlet.http.HttpServletRequest request, UUID tenant) {
    UUID actor = platform.actorFor("platform:identity:read");
    request.setAttribute(TARGET, new Target(tenant, actor));
  }

  private static Target target() {
    var attributes =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    return attributes
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
            && servlet.getRequest().getAttribute(TARGET) instanceof Target selected
        ? selected
        : null;
  }

  public static UUID targetTenant() {
    var selected = target();
    return selected == null ? null : selected.tenant();
  }

  public UUID tenantId() {
    var selected = target();
    return selected == null ? CallContexts.current().tenantId() : selected.tenant();
  }

  public String actorType() {
    return target() == null ? "TENANT" : "PLATFORM";
  }

  public UUID authenticationTenant() {
    return target() == null ? tenantId() : null;
  }

  public <T> T execute(
      com.ken.platform.foundation.data.TenantTransactions transactions,
      java.util.function.Supplier<T> operation) {
    var selected = target();
    if (selected == null) return transactions.execute(operation);
    return control.execute(status -> scope.within(selected.tenant(), operation));
  }

  public long lockCatalog() {
    long version = mapper.lockCatalog();
    var selected = target();
    if (selected != null) platform.requireActive(selected.actor());
    return version;
  }

  public UserManagementMapper.TenantRow lockTenant(UUID tenant) {
    var row = mapper.lockTenant(tenant);
    if (row == null && target() != null) throw new PlatformException("NOT_FOUND", "租户不存在", 404);
    return row;
  }

  public List<UUID> userIds(UUID actor, UUID subject) {
    if (target() != null) return subject == null ? List.of() : List.of(subject);
    return subject == null || actor.equals(subject) ? List.of(actor) : List.of(actor, subject);
  }

  public List<UserManagementMapper.UserRow> lockUsers(UUID tenant, List<UUID> ids) {
    return ids.isEmpty() ? List.of() : mapper.lockUsers(tenant, ids);
  }

  public void lockMemberRoles(UUID tenant, List<UUID> ids) {
    if (!ids.isEmpty()) mapper.lockMemberRoles(tenant, ids);
  }

  public List<UUID> actorRoles(
      UUID tenant,
      UUID actor,
      com.ken.platform.identity.dal.mapper.role.RolePermissionMapper roles) {
    return target() == null ? roles.memberRoles(tenant, actor) : List.of();
  }

  public UUID actorFor(String operation) {
    var selected = target();
    if (selected != null) {
      String permission =
          switch (operation) {
            case "identity:group:read",
                "identity:user:read",
                "identity:role:read",
                "identity:permission:read",
                "identity:session:read",
                "identity:audit:read" ->
                "platform:identity:read";
            case "identity:group:manage",
                "identity:group:assign",
                "identity:user:manage",
                "identity:role:manage",
                "identity:role:assign",
                "identity:session:manage" ->
                "platform:identity:manage";
            default -> throw new PlatformException("FORBIDDEN", "未开放的平台租户管理操作", 403);
          };
      UUID actor = platform.actorFor(permission);
      if (!actor.equals(selected.actor()))
        throw new PlatformException("INVALID_CREDENTIALS", "管理身份已变化", 401);
      if (operation.equals("identity:audit:read")) platform.actorFor("platform:audit:read");
      return actor;
    }
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
    var selected = target();
    if (selected != null) {
      return selected.tenant().equals(tenant)
          && selected.actor().equals(actor)
          && actor.equals(actorFor(operation));
    }
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
