package com.ken.platform.identity.service.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import com.ken.platform.identity.service.oauth.ProtocolAuthorizations;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只负责本地账号映射和会话，不实现外部 OAuth/OIDC 协议。 */
public final class ExternalIdentities {
  private final JdbcTemplate jdbc;
  private final LoginStore store;
  private final PasswordLoginService logins;
  private final SecurityPolicies policies;
  private final ProtocolAuthorizations authorizations;
  private final Clock clock;
  private final LoginProtection protection;
  private final TransactionTemplate tx;
  private final IdentityTenantScope tenantScope;

  public ExternalIdentities(
      JdbcTemplate jdbc,
      LoginStore store,
      PasswordLoginService logins,
      SecurityPolicies policies,
      ProtocolAuthorizations authorizations,
      Clock clock,
      PlatformTransactionManager manager,
      LoginProtection protection) {
    this.protection = protection;
    this.jdbc = jdbc;
    this.store = store;
    this.logins = logins;
    this.policies = policies;
    this.authorizations = authorizations;
    this.clock = clock;
    tenantScope = new IdentityTenantScope(jdbc, manager);
    tx = new TransactionTemplate(manager);
    tx.setTimeout(10);
  }

  public UUID tenant(String code, java.net.InetAddress source) {
    protection.begin("EXTERNAL|" + code, source, policies.current(null));
    return tx.execute(
        status -> {
          store.lockCatalog();
          var id =
              code != null && code.matches("[a-z][a-z0-9-]{0,63}")
                  ? store.resolveTenant(code)
                  : null;
          active(id);
          return id;
        });
  }

  public PasswordLoginService.Identity bindingOwner() {
    var owner = logins.requireBusinessSession();
    return tx.execute(status -> recent(owner.sessionId(), owner.tenant()));
  }

  public List<String> registrations() {
    var owner = logins.requireBusinessSession();
    if (owner.realm() != LoginRealm.TENANT) throw rejected();
    return tx.execute(
        status -> {
          return tenantScope.within(
              owner.tenant(),
              () ->
                  jdbc.queryForList(
                      "SELECT provider_registration_id FROM sys_external_identity WHERE tenant_id=? AND user_id=? ORDER BY provider_registration_id",
                      String.class,
                      owner.tenant(),
                      owner.user()));
        });
  }

  public PasswordLoginService.Identity complete(
      UUID tenant, String provider, String authority, String subject, UUID bindingSession) {
    if (subject == null || subject.isBlank() || subject.length() > 255) throw rejected();
    return tx.execute(
        status -> {
          store.lockCatalog();
          var active = active(tenant);
          return tenantScope.within(
              tenant,
              () -> {
                if (bindingSession != null) {
                  var owner = recent(bindingSession, tenant);
                  // 唯一约束同时阻止一个外部身份占用多个账号、一个账号隐式覆盖已有绑定。
                  var existing =
                      jdbc.queryForList(
                          "SELECT user_id,provider_authority FROM sys_external_identity WHERE tenant_id=? AND provider_registration_id=? AND provider_subject=?",
                          tenant,
                          provider,
                          subject);
                  if (existing.isEmpty()) {
                    jdbc.update(
                        "INSERT INTO sys_external_identity(tenant_id,user_id,provider_registration_id,provider_authority,provider_subject) VALUES(?,?,?,?,?)",
                        tenant,
                        owner.user(),
                        provider,
                        authority,
                        subject);
                  } else if (!owner.user().equals(existing.getFirst().get("user_id"))
                      || !authority.equals(existing.getFirst().get("provider_authority")))
                    throw rejected();
                  store.audit(
                      store.session(bindingSession, false),
                      "identity.external.bound",
                      UUID.randomUUID().toString());
                  // 绑定完成重新登录，避免迁移旧 Cookie 后留下失配的 PG/Redis 绑定。
                  store.revoke(bindingSession, clock.instant());
                  authorizations.revokeForSession(bindingSession);
                  return null;
                }
                var ids =
                    jdbc.queryForList(
                        "SELECT user_id FROM sys_external_identity WHERE tenant_id=? AND provider_registration_id=? AND provider_authority=? AND provider_subject=?",
                        UUID.class,
                        tenant,
                        provider,
                        authority,
                        subject);
                if (ids.isEmpty())
                  throw new PlatformException(
                      "EXTERNAL_IDENTITY_NOT_BOUND", "请先登录本租户账号完成显式绑定", 401);
                var account = store.credential(LoginRealm.TENANT, tenant, null, ids.getFirst());
                if (account == null || !account.status().equals("ACTIVE") || account.mustChange())
                  throw rejected();
                policies.current(tenant);
                var now = clock.instant();
                var session =
                    new LoginStore.Session(
                        UUID.randomUUID(),
                        LoginRealm.TENANT,
                        tenant,
                        account.id(),
                        account.authVersion(),
                        active.authVersion(),
                        false,
                        now,
                        now,
                        null,
                        null,
                        false);
                store.createSession(session);
                store.audit(session, "identity.external.login", UUID.randomUUID().toString());
                return identity(session);
              });
        });
  }

  public void unlink(String provider) {
    var owner = bindingOwner();
    tx.executeWithoutResult(
        status -> {
          recent(owner.sessionId(), owner.tenant());
          tenantScope.within(
              owner.tenant(),
              () -> {
                // 所有可绑定账号已完成本地密码初始化，因此解绑仍保留有效本地登录方式。
                var account =
                    store.credential(LoginRealm.TENANT, owner.tenant(), null, owner.user());
                if (account == null || account.hash() == null || account.mustChange())
                  throw rejected();
                jdbc.update(
                    "DELETE FROM sys_external_identity WHERE tenant_id=? AND user_id=? AND provider_registration_id=?",
                    owner.tenant(),
                    owner.user(),
                    provider);
                store.audit(
                    store.session(owner.sessionId(), false),
                    "identity.external.unbound",
                    UUID.randomUUID().toString());
                return null;
              });
        });
  }

  private PasswordLoginService.Identity recent(UUID sessionId, UUID tenant) {
    var session = store.session(sessionId, false);
    if (session == null || session.realm() != LoginRealm.TENANT || !session.tenant().equals(tenant))
      throw rejected();
    var checked = logins.checkProtocolSession(identity(session));
    if (checked == null
        || !clock
            .instant()
            .isBefore(
                session.createdAt().plusSeconds(policies.current(tenant).recentAuthSeconds())))
      throw new PlatformException("RECENT_AUTHENTICATION_REQUIRED", "请重新登录后绑定或解绑", 403);
    return checked;
  }

  private LoginStore.Tenant active(UUID tenant) {
    var value = tenant == null ? null : store.lockTenant(tenant);
    if (value == null
        || !value.status().equals("ACTIVE")
        || value.expiresAt() != null && !clock.instant().isBefore(value.expiresAt()))
      throw rejected();
    return value;
  }

  private static PasswordLoginService.Identity identity(LoginStore.Session s) {
    return new PasswordLoginService.Identity(
        s.id(),
        s.realm(),
        s.tenant(),
        s.user(),
        s.authVersion(),
        s.tenantAuthVersion(),
        s.restricted());
  }

  private static PlatformException rejected() {
    return new PlatformException("EXTERNAL_IDENTITY_REJECTED", "外部身份请求未通过校验", 401);
  }
}
