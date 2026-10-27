package com.ken.platform.identity.service.login;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.login.SessionManagementMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import com.ken.platform.identity.service.authorization.TenantManagementQueryService.Page;
import com.ken.platform.identity.service.oauth.ProtocolAuthorizations;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 租户会话治理；授权、普通目标和所有撤销事实在同一锁序/事务内重查。 */
@Service
public final class SessionManagementService {
  public record View(
      UUID id,
      @JsonProperty("user_id") UUID userId,
      String username,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("last_activity_at") Instant lastActivityAt,
      @JsonProperty("expires_at") Instant expiresAt,
      @JsonProperty("revoked_at") Instant revokedAt,
      boolean active,
      List<String> applications) {
    public View {
      applications = List.copyOf(applications);
    }
  }

  private record Access(
      UUID tenant, UUID actor, UUID target, UserManagementMapper.UserRow targetUser) {}

  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final LoginStore sessions;
  private final SessionManagementMapper queries;
  private final ProtocolAuthorizations authorizations;
  private final TenantManagementAuthorization authorization;
  private final ManagementAudits audits;
  private final SecurityPolicies policies;
  private final Clock clock;

  public SessionManagementService(
      TenantTransactions transactions,
      UserManagementMapper users,
      LoginStore sessions,
      SessionManagementMapper queries,
      ProtocolAuthorizations authorizations,
      TenantManagementAuthorization authorization,
      ManagementAudits audits,
      SecurityPolicies policies,
      Clock clock) {
    this.transactions = transactions;
    this.users = users;
    this.sessions = sessions;
    this.queries = queries;
    this.authorizations = authorizations;
    this.authorization = authorization;
    this.audits = audits;
    this.policies = policies;
    this.clock = clock;
  }

  public Page<View> list(UUID target, int page, int size) {
    return execute(
        "identity:session:read",
        ManagementAudits.Action.API_REQUEST,
        null,
        () -> target,
        access -> {
          if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new PlatformException("INVALID_ARGUMENT", "分页范围不合法", 400);
          var rows = queries.list(access.tenant(), target, page * size, size + 1);
          var policy = policies.current(access.tenant());
          Instant now = clock.instant();
          return new Page<>(
              page,
              size,
              rows.size() > size,
              rows.stream()
                  .limit(size)
                  .map(
                      row -> {
                        Instant absolute =
                            row.createdAt().plusSeconds(policy.sessionAbsoluteSeconds());
                        Instant idle =
                            row.lastActivityAt().plusSeconds(policy.sessionIdleSeconds());
                        Instant expires = absolute.isBefore(idle) ? absolute : idle;
                        boolean active =
                            row.revokedAt() == null
                                && now.isBefore(expires)
                                && !row.restricted()
                                && row.userStatus().equals("ACTIVE")
                                && !row.mustChangePassword()
                                && row.authVersion() == row.userAuthVersion()
                                && Objects.equals(
                                    row.tenantAuthVersion(), row.currentTenantAuthVersion());
                        return new View(
                            row.id(),
                            row.userId(),
                            row.username(),
                            row.displayName(),
                            row.createdAt(),
                            row.lastActivityAt(),
                            expires,
                            row.revokedAt(),
                            active,
                            sessions.sessionApplications(row.id()));
                      })
                  .toList());
        });
  }

  public void revoke(UUID id) {
    execute(
        "identity:session:manage",
        ManagementAudits.Action.SESSION_REVOKE,
        id,
        () -> targetSession(id, false).user(),
        access -> {
          var row = targetSession(id, true);
          if (!row.user().equals(access.target())) throw missing();
          if (row.revokedAt() == null) {
            sessions.revoke(row.id(), clock.instant());
            authorizations.revokeForSession(row.id());
            audit(access, "identity.managed-session.revoked", row.id(), 1);
          }
          return null;
        });
  }

  public int signOutAll(UUID user) {
    if (user == null) throw new PlatformException("INVALID_ARGUMENT", "账号标识不合法", 400);
    return execute(
        "identity:session:manage",
        ManagementAudits.Action.USER_SIGN_OUT_ALL,
        user,
        () -> user,
        access -> {
          var rows =
              sessions.sessionsForTenantUser(access.tenant(), user).stream()
                  .filter(row -> row.revokedAt() == null)
                  .toList();
          if (rows.isEmpty()) return 0;
          if (users.incrementAuthVersion(access.tenant(), user, access.targetUser().authVersion())
              != 1) throw new PlatformException("USER_VERSION_CONFLICT", "账号已变化，请刷新后操作", 409);
          Instant now = clock.instant();
          for (var row : rows) {
            sessions.revoke(row.id(), now);
            authorizations.revokeForSession(row.id());
          }
          audit(access, "identity.user.signed-out-all", user, rows.size());
          return rows.size();
        });
  }

  private void audit(Access access, String action, UUID target, int count) {
    queries.audit(
        access.actor(),
        access.tenant(),
        action,
        target.toString(),
        access.target(),
        count,
        CallContexts.current().requestId());
  }

  private LoginStore.Session targetSession(UUID id, boolean lock) {
    var row = id == null ? null : sessions.session(id, lock);
    if (row == null
        || row.realm() != LoginRealm.TENANT
        || !row.tenant().equals(CallContexts.current().tenantId())) throw missing();
    return row;
  }

  private <T> T execute(
      String operation,
      ManagementAudits.Action action,
      UUID targetId,
      Supplier<UUID> target,
      Function<Access, T> command) {
    return audits.attempt(
        action,
        targetId,
        () -> {
          UUID actor = authorization.actorFor(operation);
          return transactions.execute(
              () -> {
                UUID tenant = CallContexts.current().tenantId();
                users.lockCatalog();
                var row = users.lockTenant(tenant);
                if (row == null
                    || !row.status().equals("ACTIVE")
                    || row.expiresAt() != null && !clock.instant().isBefore(row.expiresAt()))
                  throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                UUID selected = target.get();
                var ids =
                    selected == null || selected.equals(actor)
                        ? List.of(actor)
                        : List.of(actor, selected);
                users.lockMemberRoles(tenant, ids);
                var accounts = users.lockUsers(tenant, ids);
                authorization.requireProtectedAdministrator(tenant, actor, operation, accounts);
                UserManagementMapper.UserRow selectedUser = null;
                if (selected != null) {
                  selectedUser =
                      accounts.stream()
                          .filter(account -> account.id().equals(selected))
                          .findFirst()
                          .orElseThrow(SessionManagementService::missing);
                }
                return command.apply(new Access(tenant, actor, selected, selectedUser));
              });
        });
  }

  private static PlatformException missing() {
    return new PlatformException("SESSION_TARGET_NOT_FOUND", "会话或账号不存在或不可见", 404);
  }
}
