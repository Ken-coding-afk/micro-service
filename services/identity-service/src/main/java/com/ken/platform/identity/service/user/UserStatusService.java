package com.ken.platform.identity.service.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 账号启停：受验证主体→固定顺序锁→提交时重查→状态/撤销版本/审计/Outbox 原子写入。 */
@Service
public final class UserStatusService {
  public enum Status {
    ACTIVE,
    DISABLED
  }

  public record Result(
      UUID id, Status status, long version, @JsonProperty("auth_version") long authVersion) {}

  private static final String OPERATION = "identity:user:manage";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ManagementAudits audits;
  private final TenantTransactions transactions;
  private final UserManagementMapper mapper;
  private final Clock clock;
  private final TenantManagementAuthorization authorization;

  public UserStatusService(
      TenantTransactions transactions,
      UserManagementMapper mapper,
      TenantManagementAuthorization authorization,
      Clock clock,
      ManagementAudits audits) {
    this.audits = audits;
    this.transactions = transactions;
    this.mapper = mapper;
    this.clock = clock;
    this.authorization = authorization;
  }

  public Result changeStatus(UUID target, Status requested, long expectedVersion) {
    return audits.attempt(
        ManagementAudits.Action.USER_STATUS,
        target,
        () -> changeStatusInternal(target, requested, expectedVersion));
  }

  private Result changeStatusInternal(UUID target, Status requested, long expectedVersion) {
    if (requested == null) {
      throw new PlatformException("INVALID_ARGUMENT", "账号状态参数不合法", 400);
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
          List<UUID> affected = authorization.userIds(actor, target);
          authorization.lockMemberRoles(tenant, affected);
          var users = authorization.lockUsers(tenant, affected);
          authorization.requireProtectedAdministrator(tenant, actor, OPERATION, users);
          var user =
              users.stream()
                  .filter(value -> value.id().equals(target))
                  .findFirst()
                  .orElseThrow(() -> new PlatformException("NOT_FOUND", "账号不存在或不可见", 404));
          if (user.version() != expectedVersion) {
            throw conflict("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作");
          }
          if (user.status().equals("PENDING_ACTIVATION")) {
            throw new PlatformException("ACCOUNT_ACTIVATION_REQUIRED", "待激活账号须完成独立激活流程", 400);
          }
          if (user.status().equals(requested.name())) {
            return new Result(user.id(), requested, user.version(), user.authVersion());
          }
          if (requested == Status.DISABLED
              && !user.mustChangePassword()
              && mapper.hasProtectedRole(tenant, target)
              && mapper.countEffectiveAdministrators(tenant) <= 1) {
            throw conflict("LAST_TENANT_ADMIN", "必须保留至少一个有效租户管理员");
          }
          if (mapper.setStatus(tenant, target, requested.name(), expectedVersion) != 1) {
            throw conflict("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作");
          }
          long version = user.version() + 1;
          mapper.auditStatus(
              actor,
              authorization.actorType(),
              tenant,
              target.toString(),
              context.requestId(),
              JSON.writeValueAsString(
                  Map.of("previous_status", user.status(), "status", requested.name())));
          mapper.statusEvent(
              UUID.randomUUID(),
              requested == Status.DISABLED
                  ? "identity.user.disabled.v1"
                  : "identity.user.restored.v1",
              tenant,
              target,
              version,
              clock.instant(),
              context.traceparent(),
              JSON.writeValueAsString(
                  Map.of("user_id", target.toString(), "user_version", version)));
          return new Result(target, requested, version, user.authVersion() + 1);
        });
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
