package com.ken.platform.identity.service.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.account.AccountFields;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 普通资料只维护显示名；登录名、联系方式、凭据、状态和授权不通过此用例修改。 */
@Service
public final class UserProfileService {
  public record View(UUID id, @JsonProperty("display_name") String displayName, long version) {}

  private static final String OPERATION = "identity:user:manage";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final TenantManagementAuthorization authorization;
  private final ManagementAudits audits;
  private final Clock clock;

  public UserProfileService(
      TenantTransactions transactions,
      UserManagementMapper users,
      TenantManagementAuthorization authorization,
      ManagementAudits audits,
      Clock clock) {
    this.transactions = transactions;
    this.users = users;
    this.authorization = authorization;
    this.audits = audits;
    this.clock = clock;
  }

  public View update(UUID target, long expectedVersion, String displayName) {
    return audits.attempt(
        ManagementAudits.Action.USER_PROFILE,
        target,
        () -> {
          if (target == null || expectedVersion < 0)
            throw new PlatformException("INVALID_ARGUMENT", "目标账号或版本不合法", 400);
          String name = AccountFields.displayName(displayName);
          UUID actor = authorization.actorFor(OPERATION);
          return transactions.execute(
              () -> {
                UUID tenant = CallContexts.current().tenantId();
                users.lockCatalog();
                var tenantRow = users.lockTenant(tenant);
                if (tenantRow == null
                    || !tenantRow.status().equals("ACTIVE")
                    || tenantRow.expiresAt() != null
                        && !clock.instant().isBefore(tenantRow.expiresAt()))
                  throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                var ids = actor.equals(target) ? List.of(actor) : List.of(actor, target);
                users.lockMemberRoles(tenant, ids);
                var accounts = users.lockUsers(tenant, ids);
                authorization.requireProtectedAdministrator(tenant, actor, OPERATION, accounts);
                var account =
                    accounts.stream()
                        .filter(value -> value.id().equals(target))
                        .findFirst()
                        .orElseThrow(() -> new PlatformException("NOT_FOUND", "账号不存在或不可见", 404));
                if (account.version() != expectedVersion) throw conflict();
                String previous = users.displayName(tenant, target);
                if (name.equals(previous)) return new View(target, name, account.version());
                if (users.updateDisplayName(tenant, target, expectedVersion, name) != 1)
                  throw conflict();
                users.auditProfile(
                    actor,
                    tenant,
                    target.toString(),
                    CallContexts.current().requestId(),
                    JSON.writeValueAsString(
                        Map.of(
                            "before",
                            Map.of("display_name", previous),
                            "after",
                            Map.of("display_name", name),
                            "version",
                            account.version() + 1)));
                return new View(target, name, account.version() + 1);
              });
        });
  }

  private static PlatformException conflict() {
    return new PlatformException("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作", 409);
  }
}
