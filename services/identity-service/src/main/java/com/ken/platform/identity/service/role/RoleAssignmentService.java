package com.ken.platform.identity.service.role;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.role.RoleAssignmentMapper;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class RoleAssignmentService {
  public record RoleReference(UUID id, long version) {}

  public record Result(@JsonProperty("user_id") UUID userId, long version, Set<UUID> roles) {
    public Result {
      roles = Set.copyOf(roles);
    }
  }

  private static final String OPERATION = "identity:role:assign";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ManagementAudits audits;
  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final RolePermissionMapper roles;
  private final RoleAssignmentMapper assignments;
  private final TenantManagementAuthorization authorization;
  private final Clock clock;

  public RoleAssignmentService(
      TenantTransactions transactions,
      UserManagementMapper users,
      RolePermissionMapper roles,
      RoleAssignmentMapper assignments,
      TenantManagementAuthorization authorization,
      Clock clock,
      ManagementAudits audits) {
    this.audits = audits;
    this.transactions = transactions;
    this.users = users;
    this.roles = roles;
    this.assignments = assignments;
    this.authorization = authorization;
    this.clock = clock;
  }

  public Result replace(UUID target, long expectedUserVersion, List<RoleReference> requested) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_ASSIGNMENT,
        target,
        () -> replaceInternal(target, expectedUserVersion, requested));
  }

  private Result replaceInternal(
      UUID target, long expectedUserVersion, List<RoleReference> requested) {
    if (requested == null || requested.size() > 1000) throw invalid();
    Map<UUID, Long> proposed = new HashMap<>();
    for (RoleReference role : requested) {
      if (role == null
          || role.id() == null
          || role.version() < 0
          || proposed.putIfAbsent(role.id(), role.version()) != null) {
        throw invalid();
      }
    }
    UUID actor = authorization.actorFor(OPERATION);
    return authorization.execute(
        transactions,
        () -> {
          var context = CallContexts.current();
          UUID tenant = authorization.tenantId();
          authorization.lockCatalog();
          var tenantRow = authorization.lockTenant(tenant);
          if (tenantRow == null
              || !tenantRow.status().equals("ACTIVE")
              || tenantRow.expiresAt() != null
                  && !clock.instant().isBefore(tenantRow.expiresAt())) {
            throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
          }
          Set<UUID> previous = Set.copyOf(roles.memberRoles(tenant, target));
          var all = new HashSet<>(authorization.actorRoles(tenant, actor, roles));
          all.addAll(previous);
          all.addAll(proposed.keySet());
          // 至少包含 actor 的管理员角色；空集合意味着无管理身份，仍先锁用户再失败关闭。
          var locked =
              all.isEmpty()
                  ? List.<RolePermissionMapper.RoleRow>of()
                  : roles.lockRoles(tenant, List.copyOf(all));
          var actors = authorization.lockUsers(tenant, authorization.userIds(actor, target));
          authorization.requireProtectedAdministrator(tenant, actor, OPERATION, actors);
          var user =
              actors.stream()
                  .filter(row -> row.id().equals(target))
                  .findFirst()
                  .orElseThrow(() -> new PlatformException("NOT_FOUND", "账号不存在或不可见", 404));
          if (user.version() != expectedUserVersion) {
            throw conflict("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作");
          }
          var snapshots =
              locked.stream()
                  .collect(Collectors.toMap(RolePermissionMapper.RoleRow::id, value -> value));
          proposed.forEach(
              (id, version) -> {
                var role = snapshots.get(id);
                if (role == null) {
                  throw new PlatformException("NOT_FOUND", "角色不存在或不可见", 404);
                }
                if (role.version() != version) {
                  throw conflict("ROLE_VERSION_CONFLICT", "角色已变更，请刷新后操作");
                }
                if (!role.status().equals("ACTIVE") && !previous.contains(id)) {
                  throw new PlatformException("ROLE_NOT_ASSIGNABLE", "停用角色不能重新分配", 400);
                }
              });
          boolean keepsAdmin =
              proposed.keySet().stream()
                  .map(snapshots::get)
                  .anyMatch(role -> role.protectedRole() && role.status().equals("ACTIVE"));
          if (user.status().equals("ACTIVE")
              && !user.mustChangePassword()
              && users.hasProtectedRole(tenant, target)
              && !keepsAdmin
              && users.countEffectiveAdministrators(tenant) <= 1) {
            throw conflict("LAST_TENANT_ADMIN", "必须保留至少一个有效租户管理员");
          }
          if (previous.equals(proposed.keySet())) {
            return new Result(target, user.version(), previous);
          }
          Set<UUID> changed = new HashSet<>();
          previous.stream()
              .filter(id -> !proposed.containsKey(id))
              .forEach(
                  id -> {
                    assignments.remove(tenant, target, id);
                    changed.add(id);
                  });
          proposed.keySet().stream()
              .filter(id -> !previous.contains(id))
              .forEach(
                  id -> {
                    assignments.add(tenant, target, id);
                    changed.add(id);
                  });
          for (UUID id : changed) {
            if (assignments.touchRoleReference(tenant, id, snapshots.get(id).version()) != 1) {
              throw conflict("ROLE_VERSION_CONFLICT", "角色已变更，请刷新后操作");
            }
          }
          if (assignments.touchUser(tenant, target, expectedUserVersion) != 1) {
            throw conflict("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作");
          }
          assignments.audit(
              actor,
              authorization.actorType(),
              tenant,
              target.toString(),
              context.requestId(),
              JSON.writeValueAsString(
                  Map.of(
                      "before",
                      previous.stream().map(UUID::toString).sorted().toList(),
                      "after",
                      proposed.keySet().stream().map(UUID::toString).sorted().toList(),
                      "version",
                      user.version() + 1)));
          return new Result(target, user.version() + 1, proposed.keySet());
        });
  }

  private static PlatformException invalid() {
    return new PlatformException("ROLE_ASSIGNMENT_INVALID", "角色分配参数缺失、重复或版本无效", 400);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
