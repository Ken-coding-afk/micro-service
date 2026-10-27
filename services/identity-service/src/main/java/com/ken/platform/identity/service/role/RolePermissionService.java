package com.ken.platform.identity.service.role;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class RolePermissionService {
  public record Grant(
      @JsonProperty("permission_code") String permissionCode,
      @JsonProperty("data_scope") DataScope dataScope) {}

  public record Result(
      @JsonProperty("role_id") UUID roleId,
      long version,
      @JsonProperty("authorization_version") long authorizationVersion,
      List<Grant> grants) {
    public Result {
      grants = List.copyOf(grants);
    }
  }

  private static final String OPERATION = "identity:role:manage";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ManagementAudits audits;
  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final RolePermissionMapper roles;
  private final TenantManagementAuthorization authorization;
  private final Clock clock;

  public RolePermissionService(
      TenantTransactions transactions,
      UserManagementMapper users,
      RolePermissionMapper roles,
      TenantManagementAuthorization authorization,
      Clock clock,
      ManagementAudits audits) {
    this.audits = audits;
    this.transactions = transactions;
    this.users = users;
    this.roles = roles;
    this.authorization = authorization;
    this.clock = clock;
  }

  public Result replace(
      UUID roleId, long expectedRoleVersion, long expectedCatalogVersion, List<Grant> requested) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_PERMISSIONS,
        roleId,
        () -> replaceInternal(roleId, expectedRoleVersion, expectedCatalogVersion, requested));
  }

  private Result replaceInternal(
      UUID roleId, long expectedRoleVersion, long expectedCatalogVersion, List<Grant> requested) {
    if (roleId == null
        || expectedRoleVersion < 0
        || expectedCatalogVersion < 0
        || requested == null
        || requested.size() > 1000) {
      throw invalid("角色授权参数不合法");
    }
    Map<String, DataScope> proposed = new TreeMap<>();
    for (Grant grant : requested) {
      if (grant == null
          || grant.permissionCode() == null
          || proposed.containsKey(grant.permissionCode())) {
        throw invalid("权限编码缺失或重复");
      }
      proposed.put(grant.permissionCode(), grant.dataScope());
    }
    UUID actor = authorization.actorFor(OPERATION);
    return transactions.execute(
        () -> {
          var context = CallContexts.current();
          UUID tenant = context.tenantId();
          long catalogVersion = users.lockCatalog();
          var tenantRow = users.lockTenant(tenant);
          if (tenantRow == null
              || !tenantRow.status().equals("ACTIVE")
              || tenantRow.expiresAt() != null
                  && !clock.instant().isBefore(tenantRow.expiresAt())) {
            throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
          }
          var lockedIds = new HashSet<>(roles.memberRoles(tenant, actor));
          lockedIds.add(roleId);
          var lockedRoles = roles.lockRoles(tenant, List.copyOf(lockedIds));
          var lockedUsers = users.lockUsers(tenant, List.of(actor));
          authorization.requireProtectedAdministrator(tenant, actor, OPERATION, lockedUsers);
          var role =
              lockedRoles.stream()
                  .filter(value -> value.id().equals(roleId))
                  .findFirst()
                  .orElseThrow(() -> new PlatformException("NOT_FOUND", "角色不存在或不可见", 404));
          if (role.protectedRole()) {
            throw new PlatformException("PROTECTED_ROLE", "内置管理员角色不允许在普通编辑入口修改", 403);
          }
          if (role.version() != expectedRoleVersion || catalogVersion != expectedCatalogVersion) {
            throw new PlatformException(
                "ROLE_OR_CATALOG_VERSION_CONFLICT", "角色或权限目录已变化，请刷新后操作", 409);
          }
          Map<String, DataScope> previous = new TreeMap<>();
          roles
              .grants(tenant, roleId)
              .forEach(
                  grant ->
                      previous.put(
                          grant.permissionCode(),
                          grant.dataScope() == null ? null : DataScope.valueOf(grant.dataScope())));
          var catalog =
              roles.catalog().stream()
                  .collect(
                      Collectors.toMap(RolePermissionMapper.PermissionRow::code, value -> value));
          proposed.forEach(
              (code, scope) -> {
                var permission = catalog.get(code);
                if (permission == null || !permission.principalType().equals("TENANT")) {
                  throw invalid("权限不存在或不属于租户目录");
                }
                boolean retained =
                    previous.containsKey(code) && Objects.equals(previous.get(code), scope);
                if (code.startsWith("identity:") && !retained) {
                  throw invalid("身份管理能力由内置管理员角色提供，不能授予普通业务角色");
                }
                if (!permission.status().equals("ACTIVE") && !retained) {
                  throw invalid("历史只读权限不能重新授予或扩大范围");
                }
                List<String> supported =
                    permission.scopes() == null || permission.scopes().isEmpty()
                        ? List.of()
                        : Arrays.asList(permission.scopes().split(","));
                if (scope == null ? !supported.isEmpty() : !supported.contains(scope.name())) {
                  throw invalid("权限数据范围不符合服务声明");
                }
              });
          if (previous.equals(proposed)) {
            return new Result(
                roleId, role.version(), role.authorizationVersion(), entries(proposed));
          }
          previous.forEach(
              (code, scope) -> {
                if (!proposed.containsKey(code) || !Objects.equals(proposed.get(code), scope)) {
                  roles.removeGrant(tenant, roleId, code);
                }
              });
          proposed.forEach(
              (code, scope) -> {
                if (!previous.containsKey(code) || !Objects.equals(previous.get(code), scope)) {
                  roles.addGrant(tenant, roleId, code, scope == null ? null : scope.name());
                }
              });
          if (roles.touchAuthorization(tenant, roleId, expectedRoleVersion) != 1) {
            throw new PlatformException("ROLE_VERSION_CONFLICT", "角色已变更，请刷新后操作", 409);
          }
          roles.audit(
              actor,
              tenant,
              roleId.toString(),
              context.requestId(),
              JSON.writeValueAsString(
                  Map.of(
                      "before",
                      entries(previous),
                      "after",
                      entries(proposed),
                      "version",
                      role.version() + 1)));
          return new Result(
              roleId, role.version() + 1, role.authorizationVersion() + 1, entries(proposed));
        });
  }

  private static List<Grant> entries(Map<String, DataScope> values) {
    return values.entrySet().stream()
        .map(entry -> new Grant(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("ROLE_PERMISSION_INVALID", message, 400);
  }
}
