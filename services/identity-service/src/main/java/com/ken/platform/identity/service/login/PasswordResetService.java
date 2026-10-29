package com.ken.platform.identity.service.login;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.login.PasswordResetStore;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import com.ken.platform.identity.service.oauth.ProtocolAuthorizations;
import com.ken.platform.identity.service.security.*;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class PasswordResetService {
  public record Issued(
      @JsonProperty("reset_token") String resetToken,
      @JsonProperty("expires_at") Instant expiresAt) {
    @Override
    public String toString() {
      return "Issued[REDACTED]";
    }
  }

  private final PasswordResetStore store;
  private final TenantManagementAuthorization authorization;
  private final UserManagementMapper users;
  private final AccountManagementMapper accounts;
  private final TenantTransactions tenants;
  private final IdentityTenantScope scope;
  private final TransactionTemplate control;
  private final PasswordCredentials passwords;
  private final SecurityPolicies policies;
  private final RecentAuthentication recent;
  private final LoginProtection protection;
  private final LoginStore sessions;
  private final ProtocolAuthorizations authorizations;
  private final ManagementAudits audits;
  private final Clock clock;
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public PasswordResetService(
      PasswordResetStore store,
      TenantManagementAuthorization authorization,
      UserManagementMapper users,
      AccountManagementMapper accounts,
      TenantTransactions tenants,
      IdentityTenantScope scope,
      PlatformTransactionManager manager,
      PasswordCredentials passwords,
      SecurityPolicies policies,
      RecentAuthentication recent,
      LoginProtection protection,
      LoginStore sessions,
      ProtocolAuthorizations authorizations,
      ManagementAudits audits,
      Clock clock) {
    this.store = store;
    this.authorization = authorization;
    this.users = users;
    this.accounts = accounts;
    this.tenants = tenants;
    this.scope = scope;
    this.control = new TransactionTemplate(manager);
    this.control.setTimeout(10);
    this.passwords = passwords;
    this.policies = policies;
    this.recent = recent;
    this.protection = protection;
    this.sessions = sessions;
    this.authorizations = authorizations;
    this.audits = audits;
    this.clock = clock;
  }

  public Issued issue(UUID target, long version, InetAddress source) {
    return audits.attempt(
        ManagementAudits.Action.PASSWORD_RESET_ISSUE,
        target,
        () -> {
          UUID actor = authorization.actorFor("identity:user:manage");
          var policy = policies.current(authorization.authenticationTenant());
          var limit = protection.begin("reset-issue:" + actor, source, policy);
          try {
            Issued result =
                authorization.execute(
                    tenants,
                    () -> {
                      UUID tenant = authorization.tenantId();
                      authorization.lockCatalog();
                      activeTenant(tenant);
                      var ids = authorization.userIds(actor, target);
                      authorization.lockMemberRoles(tenant, ids);
                      var rows = authorization.lockUsers(tenant, ids);
                      authorization.requireProtectedAdministrator(
                          tenant, actor, "identity:user:manage", rows);
                      recent.require(authorization.authenticationTenant());
                      var user =
                          rows.stream()
                              .filter(r -> r.id().equals(target))
                              .findFirst()
                              .orElseThrow(
                                  () -> new PlatformException("NOT_FOUND", "账号不存在或不可见", 404));
                      if (user.version() != version)
                        throw new PlatformException("USER_VERSION_CONFLICT", "账号已变化，请刷新", 409);
                      if (user.status().equals("DISABLED"))
                        throw new PlatformException("ACCOUNT_DISABLED", "停用账号不能重置密码", 403);
                      String requestId = request();
                      String operation = "identity.password-reset.issued";
                      String commandDigest = LoginProtection.digest(target + ":" + version);
                      var prior =
                          accounts.prior(
                              authorization.actorType(), actor, tenant, operation, requestId);
                      if (prior != null)
                        throw new PlatformException(
                            "PASSWORD_RESET_ALREADY_ISSUED", "本次凭证已签发且不能再次展示，请核对交付结果", 409);
                      byte[] bytes = new byte[32];
                      random.nextBytes(bytes);
                      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                      Arrays.fill(bytes, (byte) 0);
                      Instant expires = clock.instant().plusSeconds(900);
                      store.issue(
                          LoginProtection.digest(token),
                          tenant,
                          target,
                          user.authVersion(),
                          expires);
                      accounts.remember(
                          authorization.actorType(),
                          actor,
                          tenant,
                          operation,
                          requestId,
                          commandDigest,
                          "{}");
                      accounts.audit(
                          actor,
                          authorization.actorType(),
                          tenant,
                          "identity.password-reset.issued",
                          target.toString(),
                          request(),
                          "{\"credential_issued\":true}");
                      return new Issued(token, expires);
                    });
            protection.success(limit, policy);
            return result;
          } catch (RuntimeException failure) {
            protection.failure(limit, policy);
            throw failure;
          }
        });
  }

  public void consume(String token, String password, String confirmation, InetAddress source) {
    var policy = policies.current(null);
    var limit =
        protection.begin(
            "reset-consume:" + LoginProtection.digest(Objects.toString(token, "")), source, policy);
    try {
      audits.attempt(
          ManagementAudits.Action.PASSWORD_RESET_CONSUME,
          null,
          () ->
              control.execute(
                  status -> {
                    users.lockCatalog();
                    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
                    String digest = LoginProtection.digest(token);
                    var observed = store.find(digest);
                    if (observed == null) throw invalid();
                    activeTenant(observed.tenant());
                    return scope.within(
                        observed.tenant(),
                        () -> {
                          var rows = users.lockUsers(observed.tenant(), List.of(observed.user()));
                          if (rows.isEmpty()
                              || rows.getFirst().status().equals("DISABLED")
                              || rows.getFirst().authVersion() != observed.authVersion()
                              || observed.consumed() != null
                              || !clock.instant().isBefore(observed.expires())) throw invalid();
                          if (!Objects.equals(password, confirmation))
                            throw new PlatformException(
                                "PASSWORD_CONFIRM_MISMATCH", "两次密码不一致", 400);
                          if (passwords.matches(
                              password, store.password(observed.tenant(), observed.user())))
                            throw new PlatformException("PASSWORD_REUSE", "新密码不能与原密码相同", 400);
                          String encoded =
                              passwords.encode(password, policies.current(observed.tenant()));
                          if (!store.consume(digest, clock.instant())
                              || store.change(
                                      observed.tenant(),
                                      observed.user(),
                                      observed.authVersion(),
                                      encoded)
                                  != 1) throw invalid();
                          for (var session :
                              sessions.sessionsForTenantUser(observed.tenant(), observed.user())) {
                            sessions.revoke(session.id(), clock.instant());
                            authorizations.revokeForSession(session.id());
                          }
                          accounts.audit(
                              observed.user(),
                              "TENANT",
                              observed.tenant(),
                              "identity.password-reset.consumed",
                              observed.user().toString(),
                              request(),
                              "{}");
                          return null;
                        });
                  }));
      protection.success(limit, policy);
    } catch (RuntimeException failure) {
      protection.failure(limit, policy);
      throw failure;
    }
  }

  private void activeTenant(UUID tenant) {
    var row = users.lockTenant(tenant);
    if (row == null) throw new PlatformException("NOT_FOUND", "租户不存在", 404);
    if (!row.status().equals("ACTIVE")
        || row.expiresAt() != null && !clock.instant().isBefore(row.expiresAt()))
      throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
  }

  private static String request() {
    var context = CallContexts.current();
    return context == null ? UUID.randomUUID().toString() : context.requestId();
  }

  private static PlatformException invalid() {
    return new PlatformException("PASSWORD_RESET_INVALID", "重置凭证无效、过期或已使用", 400);
  }
}
