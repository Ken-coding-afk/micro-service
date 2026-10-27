package com.ken.platform.identity.service.account;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.account.PlatformLifecycleMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class PlatformLifecycleService {
  public enum Status {
    ACTIVE,
    DISABLED
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final TransactionTemplate transactions;
  private final UserManagementMapper users;
  private final PlatformLifecycleMapper lifecycle;
  private final AccountManagementMapper accounts;
  private final PlatformManagementAuthorization authorization;
  private final ManagementAudits audits;

  public PlatformLifecycleService(
      PlatformTransactionManager manager,
      UserManagementMapper users,
      PlatformLifecycleMapper lifecycle,
      AccountManagementMapper accounts,
      PlatformManagementAuthorization authorization,
      ManagementAudits audits) {
    this.transactions = new TransactionTemplate(manager);
    this.users = users;
    this.lifecycle = lifecycle;
    this.accounts = accounts;
    this.authorization = authorization;
    this.audits = audits;
  }

  public PlatformLifecycleMapper.Administrator updateAdministrator(
      UUID target, long expectedVersion, String displayName, Status requested) {
    return audits.attempt(
        ManagementAudits.Action.ADMINISTRATOR_UPDATE,
        target,
        () -> {
          valid(target, expectedVersion, requested);
          String name = AccountFields.displayName(displayName);
          UUID actor = authorization.actorFor("platform:admin:manage");
          return transactions.execute(
              status -> {
                users.lockCatalog();
                var locked =
                    lifecycle.lockAdministrators(
                        actor.equals(target) ? List.of(actor) : List.of(actor, target));
                var acting =
                    locked.stream()
                        .filter(row -> row.id().equals(actor))
                        .findFirst()
                        .orElseThrow(PlatformLifecycleService::invalidActor);
                if (!acting.status().equals("ACTIVE") || acting.mustChangePassword()) {
                  throw invalidActor();
                }
                var row =
                    locked.stream()
                        .filter(value -> value.id().equals(target))
                        .findFirst()
                        .orElseThrow(() -> new PlatformException("NOT_FOUND", "平台账号不存在", 404));
                if (row.version() != expectedVersion) {
                  throw conflict("PLATFORM_USER_VERSION_CONFLICT");
                }
                boolean stateChanged = !row.status().equals(requested.name());
                if (!stateChanged && row.name().equals(name)) {
                  return row;
                }
                if (stateChanged
                    && requested == Status.DISABLED
                    && row.status().equals("ACTIVE")
                    && !row.mustChangePassword()
                    && lifecycle.effectiveAdministratorCount() <= 1) {
                  throw conflict("LAST_PLATFORM_ADMIN");
                }
                int revoke = stateChanged ? 1 : 0;
                if (lifecycle.updateAdministrator(
                        target, expectedVersion, name, requested.name(), revoke)
                    != 1) {
                  throw conflict("PLATFORM_USER_VERSION_CONFLICT");
                }
                accounts.audit(
                    actor,
                    "PLATFORM",
                    null,
                    "platform.admin.updated",
                    target.toString(),
                    CallContexts.current().requestId(),
                    JSON.writeValueAsString(
                        Map.of(
                            "previous_status",
                            row.status(),
                            "status",
                            requested.name(),
                            "display_name_changed",
                            !row.name().equals(name))));
                return new PlatformLifecycleMapper.Administrator(
                    target,
                    name,
                    requested.name(),
                    row.mustChangePassword(),
                    row.version() + 1,
                    row.authVersion() + revoke);
              });
        });
  }

  public PlatformLifecycleMapper.Tenant updateTenant(
      UUID target, long expectedVersion, String displayName, Instant expiresAt, Status requested) {
    return audits.attempt(
        ManagementAudits.Action.TENANT_UPDATE,
        target,
        () -> {
          valid(target, expectedVersion, requested);
          String name = AccountFields.displayName(displayName);
          UUID actor = authorization.actorFor("platform:tenant:manage");
          Instant expiry =
              expiresAt == null
                  ? null
                  : expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          return transactions.execute(
              status -> {
                users.lockCatalog();
                var row = lifecycle.lockTenant(target);
                authorization.requireActive(actor);
                if (row == null) {
                  throw new PlatformException("NOT_FOUND", "租户不存在", 404);
                }
                if (row.version() != expectedVersion) {
                  throw conflict("TENANT_VERSION_CONFLICT");
                }
                boolean stateChanged = !row.status().equals(requested.name());
                if (!stateChanged
                    && row.name().equals(name)
                    && Objects.equals(row.expiresAt(), expiry)) {
                  return row;
                }
                int revoke = stateChanged ? 1 : 0;
                if (lifecycle.updateTenant(
                        target, expectedVersion, name, expiry, requested.name(), revoke)
                    != 1) {
                  throw conflict("TENANT_VERSION_CONFLICT");
                }
                accounts.audit(
                    actor,
                    "PLATFORM",
                    target,
                    "platform.tenant.updated",
                    target.toString(),
                    CallContexts.current().requestId(),
                    JSON.writeValueAsString(
                        Map.of(
                            "previous_status",
                            row.status(),
                            "status",
                            requested.name(),
                            "name_changed",
                            !row.name().equals(name),
                            "expiry_changed",
                            !Objects.equals(row.expiresAt(), expiry))));
                return new PlatformLifecycleMapper.Tenant(
                    target,
                    name,
                    requested.name(),
                    expiry,
                    row.version() + 1,
                    row.authVersion() + revoke);
              });
        });
  }

  private static void valid(UUID target, long expectedVersion, Status status) {
    if (target == null || expectedVersion < 0 || status == null) {
      throw new PlatformException("INVALID_ARGUMENT", "管理参数不合法", 400);
    }
  }

  private static PlatformException invalidActor() {
    return new PlatformException("INVALID_CREDENTIALS", "当前平台身份不可用", 401);
  }

  private static PlatformException conflict(String code) {
    return new PlatformException(
        code, code.equals("LAST_PLATFORM_ADMIN") ? "必须保留至少一个有效平台管理员" : "记录已变化，请刷新后操作", 409);
  }
}
