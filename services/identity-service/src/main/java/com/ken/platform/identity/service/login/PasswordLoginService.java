package com.ken.platform.identity.service.login;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.login.LoginStore.Credential;
import com.ken.platform.identity.dal.mapper.login.LoginStore.Session;
import com.ken.platform.identity.dal.mapper.login.LoginStore.Tenant;
import com.ken.platform.identity.service.account.AccountFields;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.io.Serializable;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 可信登录适配器调用的用例；预认证绑定不等于业务主体，首次改密后必须重新登录。 */
@Service
public final class PasswordLoginService {
  public record Identity(
      UUID sessionId,
      LoginRealm realm,
      UUID tenant,
      UUID user,
      long authVersion,
      Long tenantAuthVersion,
      boolean restricted)
      implements Serializable, org.springframework.security.core.AuthenticatedPrincipal {
    @Override
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getName() {
      return user.toString();
    }
  }

  private record Admission(
      Identity identity, LoginProtection.Ticket ticket, SecurityPolicies.Effective policy) {}

  private record Checked(Session session, Credential credential, String error) {}

  public record SessionSummary(
      UUID id,
      @com.fasterxml.jackson.annotation.JsonProperty("created_at") Instant createdAt,
      @com.fasterxml.jackson.annotation.JsonProperty("last_activity_at") Instant lastActivityAt,
      @com.fasterxml.jackson.annotation.JsonProperty("expires_at") Instant expiresAt,
      @com.fasterxml.jackson.annotation.JsonProperty("revoked_at") Instant revokedAt,
      boolean current,
      boolean active,
      java.util.List<String> applications) {
    public SessionSummary {
      applications = java.util.List.copyOf(applications);
    }
  }

  public record ProfileView(
      UUID id,
      String username,
      @com.fasterxml.jackson.annotation.JsonProperty("display_name") String displayName,
      long version) {}

  private record SessionResult<T>(Checked checked, T value) {}

  private static final String DUMMY =
      "{bcrypt}" + new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());
  private final LoginStore store;
  private final LoginProtection protection;
  private final PasswordCredentials passwords;
  private final SecurityPolicies policies;
  private final SecurityAuditRepository audits;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final TransactionTemplate events;
  private final com.ken.platform.identity.service.oauth.ProtocolAuthorizations authorizations;

  public PasswordLoginService(
      LoginStore store,
      LoginProtection protection,
      PasswordCredentials passwords,
      SecurityPolicies policies,
      SecurityAuditRepository audits,
      Clock clock,
      PlatformTransactionManager manager,
      com.ken.platform.identity.service.oauth.ProtocolAuthorizations authorizations) {
    this.authorizations = Objects.requireNonNull(authorizations);
    this.store = store;
    this.protection = protection;
    this.passwords = passwords;
    this.policies = policies;
    this.audits = audits;
    this.clock = clock;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  public Identity authenticate(
      LoginRealm realm,
      String tenantCode,
      String username,
      String password,
      InetAddress trustedSource) {
    ownBoundary();
    var resolvedTenant = new java.util.concurrent.atomic.AtomicReference<UUID>();
    return attempt(
        "identity.password.login",
        resolvedTenant,
        () -> {
          Admission admission =
              transactions.execute(
                  status -> {
                    store.lockCatalog();
                    boolean realmAllowed =
                        realm != null
                            && !(realm == LoginRealm.PLATFORM
                                && tenantCode != null
                                && !tenantCode.isBlank());
                    UUID tenantId =
                        realm == LoginRealm.TENANT
                                && tenantCode != null
                                && tenantCode.matches("[a-z][a-z0-9-]{0,63}")
                            ? store.resolveTenant(tenantCode)
                            : null;
                    Tenant tenant = tenantId == null ? null : store.lockTenant(tenantId);
                    resolvedTenant.set(tenant == null ? null : tenant.id());
                    var policy = policies.current(tenantId);
                    String normalized;
                    boolean syntax;
                    try {
                      normalized = AccountFields.username(username);
                      syntax = true;
                    } catch (PlatformException rejected) {
                      normalized =
                          "invalid:" + LoginProtection.digest(username == null ? "" : username);
                      syntax = false;
                    }
                    String subject =
                        realm
                            + "|"
                            + (tenantId == null
                                ? "unknown:" + Objects.toString(tenantCode, "")
                                : tenantId)
                            + "|"
                            + normalized;
                    var ticket = protection.begin(subject, trustedSource, policy);
                    boolean tenantAllowed =
                        realmAllowed && (realm == LoginRealm.PLATFORM || active(tenant));
                    var row =
                        syntax && tenantAllowed
                            ? store.credential(realm, tenantId, normalized, null)
                            : null;
                    boolean realHash = row != null && row.hash() != null;
                    // UserDetailsService 绑定本次已持锁的租户/账号快照，不缓存跨租户账号。
                    // 状态仍在耗时密码校验后检查，未知账号也使用 dummy hash，保留失败限速边界。
                    String storedHash = realHash ? row.hash() : DUMMY;
                    var provider =
                        new DaoAuthenticationProvider(
                            name ->
                                User.withUsername(subject)
                                    .password(storedHash)
                                    .authorities("LOGIN")
                                    .build());
                    provider.setPasswordEncoder(passwords.encoder());
                    boolean matches;
                    try {
                      if (!PasswordCredentials.bounded(password))
                        throw new org.springframework.security.authentication
                            .BadCredentialsException("凭证输入不合法");
                      provider.authenticate(
                          UsernamePasswordAuthenticationToken.unauthenticated(subject, password));
                      matches = true;
                    } catch (AuthenticationException | IllegalArgumentException rejected) {
                      matches = false;
                    }
                    boolean accountAllowed =
                        row != null
                            && (row.status().equals("ACTIVE")
                                || row.status().equals("PENDING_ACTIVATION") && row.mustChange());
                    if (!matches || !realHash || !accountAllowed || !tenantAllowed) {
                      protection.failure(ticket, policy);
                      throw invalid();
                    }
                    Instant now = now();
                    var session =
                        new Session(
                            UUID.randomUUID(),
                            realm,
                            tenantId,
                            row.id(),
                            row.authVersion(),
                            tenant == null ? null : tenant.authVersion(),
                            row.mustChange(),
                            now,
                            now,
                            null,
                            null,
                            false);
                    store.createSession(session);
                    // 该事件描述数据库绑定创建；Redis 完成检查失败则不交付身份并撤销此绑定。
                    store.audit(session, "identity.session.created", request());
                    return new Admission(identity(session), ticket, policy);
                  });
          try {
            protection.success(admission.ticket(), admission.policy());
          } catch (RuntimeException failedDelivery) {
            try {
              transactions.execute(
                  status -> {
                    revoke(admission.identity().sessionId(), now());
                    return null;
                  });
            } catch (RuntimeException unavailable) {
              throw new PlatformException("LOGIN_SESSION_UNAVAILABLE", "登录会话当前不可用", 503);
            }
            throw failedDelivery;
          }
          return admission.identity();
        });
  }

  /** 当前 Cookie/预认证主体必须由 Spring Security 提供，禁止从请求 JSON 构造 Identity。 */
  public Identity requireBusinessSession() {
    ownBoundary();
    return attempt(
        "identity.session.checked",
        () -> {
          var principal = principal();
          var checked = transactions.execute(status -> check(principal, false));
          if (checked.error() != null) throw checkError(checked.error());
          if (checked.session().restricted())
            throw new PlatformException("PASSWORD_CHANGE_REQUIRED", "请先完成首次改密", 403);
          return identity(checked.session());
        });
  }

  /** 仅供协议事务使用；返回 null 后调用者提交撤销事实再返回协议错误，不能回滚到期撤销。 */
  public Identity checkProtocolSession(Identity identity) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("协议会话检查必须位于协议事务内");
    var checked = check(identity, false);
    return checked.error() == null && !checked.session().restricted()
        ? identity(checked.session())
        : null;
  }

  public void verifyBrowserBinding(String redisId) {
    ownBoundary();
    attempt(
        "identity.session.browser",
        () -> {
          var owner = principal();
          var bound = store.session(owner.sessionId(), false);
          if (bound == null
              || !identity(bound).equals(owner)
              || !Objects.equals(bound.redisSessionId(), redisId)) throw invalid();
          return null;
        });
  }

  public Identity requireInitialSession() {
    ownBoundary();
    return attempt(
        "identity.session.checked",
        () -> {
          var value = transactions.execute(status -> check(principal(), true));
          if (value.error() != null) throw checkError(value.error());
          if (!value.session().restricted()) throw checkError("FIRST_PASSWORD_NOT_REQUIRED");
          return identity(value.session());
        });
  }

  public int bindRedisSession(String redisId) {
    ownBoundary();
    if (redisId == null || redisId.isBlank() || redisId.length() > 100) throw invalid();
    return attempt(
        "identity.session.attached",
        () -> {
          var value =
              transactions.execute(
                  status -> {
                    var checked = check(principal(), false);
                    if (checked.error() == null) {
                      if (store.attachRedisSession(checked.session().id(), redisId) != 1)
                        throw invalid();
                      store.audit(checked.session(), "identity.session.attached", request());
                    }
                    return checked;
                  });
          if (value.error() != null) throw checkError(value.error());
          return policies.current(value.session().tenant()).sessionIdleSeconds();
        });
  }

  public Identity revokeCurrentSession() {
    ownBoundary();
    return attempt(
        "identity.session.revoked",
        () ->
            transactions.execute(
                status -> {
                  store.lockCatalog();
                  var owner = principal();
                  var current = store.session(owner.sessionId(), true);
                  if (current == null || !identity(current).equals(owner)) throw invalid();
                  revoke(current.id(), now());
                  if (current.revokedAt() == null)
                    store.audit(current, "identity.session.revoked", request());
                  return owner;
                }));
  }

  /** 仅供已验证的身份 SSO 适配器调用；响应不含 Redis ID、Cookie 或协议令牌。 */
  public java.util.List<SessionSummary> ownSessions(int offset, int limit) {
    return ownSessionOperation(
        "identity.sessions.read",
        false,
        checked -> {
          if (offset < 0 || limit < 1 || limit > 100)
            throw new PlatformException("INVALID_PAGINATION", "分页范围不合法", 400);
          var owner = checked.session();
          var policy = policies.current(owner.tenant());
          Instant now = now();
          return store.ownSessions(owner, limit, offset, false).stream()
              .map(
                  row -> {
                    Instant absolute = row.createdAt().plusSeconds(policy.sessionAbsoluteSeconds());
                    Instant idle = row.lastActivity().plusSeconds(policy.sessionIdleSeconds());
                    Instant expires = absolute.isBefore(idle) ? absolute : idle;
                    boolean active =
                        row.revokedAt() == null
                            && now.isBefore(expires)
                            && row.authVersion() == owner.authVersion()
                            && Objects.equals(row.tenantAuthVersion(), owner.tenantAuthVersion())
                            && !row.restricted();
                    return new SessionSummary(
                        row.id(),
                        row.createdAt(),
                        row.lastActivity(),
                        expires,
                        row.revokedAt(),
                        row.id().equals(owner.id()),
                        active,
                        store.sessionApplications(row.id()));
                  })
              .toList();
        });
  }

  public void revokeOwnSession(UUID target) {
    boolean current =
        ownSessionOperation(
            "identity.session.revoked",
            true,
            checked -> {
              if (target == null) throw new PlatformException("SESSION_NOT_FOUND", "会话不存在", 404);
              var owner = checked.session();
              var row = store.session(target, true);
              if (row == null
                  || row.realm() != owner.realm()
                  || !row.user().equals(owner.user())
                  || !Objects.equals(row.tenant(), owner.tenant()))
                throw new PlatformException("SESSION_NOT_FOUND", "会话不存在", 404);
              if (row.revokedAt() == null) {
                revoke(row.id(), now());
                store.audit(row, "identity.session.revoked", request());
              }
              return target.equals(owner.id());
            });
    if (current) SecurityContextHolder.clearContext();
  }

  public int signOutAllOwnSessions() {
    int count =
        ownSessionOperation(
            "identity.sessions.signed_out_all",
            true,
            checked -> {
              var owner = checked.session();
              // 目录互斥锁与账号锁先于全部会话/授权，阻止并发登录或签发漏出本轮退出。
              if (store.incrementAuthVersion(owner) != 1) throw invalid();
              int changed = revokeOwnBindings(owner);
              store.audit(owner, "identity.sessions.signed_out_all", request());
              return changed;
            });
    SecurityContextHolder.clearContext();
    return count;
  }

  public void changeOwnPassword(String currentPassword, String nextPassword) {
    ownSessionOperation(
        "identity.password.changed",
        true,
        checked -> {
          var owner = checked.session();
          var policy = policies.current(owner.tenant());
          if (!now().isBefore(owner.createdAt().plusSeconds(policy.recentAuthSeconds())))
            throw new PlatformException("RECENT_AUTHENTICATION_REQUIRED", "请重新认证后修改密码", 403);
          if (!passwords.matches(currentPassword, checked.credential().hash()))
            throw new PlatformException("CURRENT_PASSWORD_INVALID", "原密码无效", 400);
          if (passwords.matches(nextPassword, checked.credential().hash()))
            throw new PlatformException("PASSWORD_REUSE", "新密码不能与原密码相同", 400);
          String hash = passwords.encode(nextPassword, policy);
          if (store.changeOwnPassword(owner, hash) != 1) throw invalid();
          revokeOwnBindings(owner);
          store.audit(owner, "identity.password.changed", request());
          return null;
        });
    SecurityContextHolder.clearContext();
  }

  public ProfileView ownProfile() {
    return ownSessionOperation(
        "identity.own-profile.read", false, checked -> profileView(profile(checked.session())));
  }

  public ProfileView updateOwnProfile(long expectedVersion, String displayName) {
    return ownSessionOperation(
        "identity.own-profile.updated",
        true,
        checked -> {
          String name = AccountFields.displayName(displayName);
          var owner = checked.session();
          var previous = profile(owner);
          if (previous.version() != expectedVersion) throw profileConflict();
          if (name.equals(previous.displayName())) return profileView(previous);
          if (store.changeOwnDisplayName(owner, expectedVersion, name) != 1)
            throw profileConflict();
          store.auditOwnProfile(
              owner, previous.displayName(), name, expectedVersion + 1, request());
          return new ProfileView(previous.id(), previous.username(), name, expectedVersion + 1);
        });
  }

  private LoginStore.Profile profile(Session owner) {
    var row = store.ownProfile(owner);
    if (row == null) throw invalid();
    return row;
  }

  private static ProfileView profileView(LoginStore.Profile profile) {
    return new ProfileView(
        profile.id(), profile.username(), profile.displayName(), profile.version());
  }

  private static PlatformException profileConflict() {
    return new PlatformException("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作", 409);
  }

  private int revokeOwnBindings(Session owner) {
    int changed = 0;
    for (var row : store.ownSessions(owner, null, 0, true)) {
      if (row.revokedAt() == null) {
        revoke(row.id(), now());
        changed++;
      }
    }
    return changed;
  }

  private <T> T ownSessionOperation(
      String action, boolean write, java.util.function.Function<Checked, T> operation) {
    ownBoundary();
    return attempt(
        action,
        () -> {
          SessionResult<T> result =
              transactions.execute(
                  status -> {
                    if (write) store.lockSessionManagement();
                    var checked = check(principal(), false);
                    return new SessionResult<>(
                        checked,
                        checked.error() == null && !checked.session().restricted()
                            ? operation.apply(checked)
                            : null);
                  });
          if (result.checked().error() != null) throw checkError(result.checked().error());
          if (result.checked().session().restricted())
            throw new PlatformException("PASSWORD_CHANGE_REQUIRED", "请先完成首次改密", 403);
          return result.value();
        });
  }

  public void changeInitialPassword(String nextPassword) {
    ownBoundary();
    attempt(
        "identity.password.initial",
        () -> {
          var principal = principal();
          var checked =
              transactions.execute(
                  status -> {
                    var current = check(principal, true);
                    if (current.error() != null) return current;
                    if (!current.session().restricted() || !current.credential().mustChange()) {
                      return new Checked(
                          current.session(), current.credential(), "FIRST_PASSWORD_NOT_REQUIRED");
                    }
                    var session = current.session();
                    var row = current.credential();
                    if (passwords.matches(nextPassword, row.hash()))
                      throw new PlatformException("PASSWORD_REUSE", "新密码不能与初始密码相同", 400);
                    String hash =
                        passwords.encode(nextPassword, policies.current(session.tenant()));
                    if (store.firstPassword(session.realm(), session.tenant(), row, hash) != 1)
                      throw invalid();
                    if (session.realm() == LoginRealm.TENANT
                        && row.status().equals("PENDING_ACTIVATION")) {
                      store.activation(session.tenant(), session.user(), row.version() + 1, now());
                    }
                    revoke(session.id(), now());
                    store.audit(session, "identity.password.initial", request());
                    return current;
                  });
          if (checked.error() != null) throw checkError(checked.error());
          SecurityContextHolder.clearContext();
          return null;
        });
  }

  private Checked check(Identity identity, boolean initialPassword) {
    store.lockCatalog();
    var observed = store.session(identity.sessionId(), false);
    if (observed == null || !identity(observed).equals(identity))
      return new Checked(null, null, "INVALID_CREDENTIALS");
    var tenant = observed.tenant() == null ? null : store.lockTenant(observed.tenant());
    var credential = store.credential(observed.realm(), observed.tenant(), null, observed.user());
    var session = store.session(observed.id(), true);
    var policy = policies.current(observed.tenant());
    Instant now = now();
    boolean expired =
        !now.isBefore(session.createdAt().plusSeconds(policy.sessionAbsoluteSeconds()))
            || !now.isBefore(session.lastActivity().plusSeconds(policy.sessionIdleSeconds()))
            || initialPassword
                && !now.isBefore(session.createdAt().plusSeconds(policy.recentAuthSeconds()));
    boolean valid =
        session.revokedAt() == null
            && credential != null
            && credential.authVersion() == session.authVersion()
            && (credential.status().equals("ACTIVE")
                || session.restricted() && credential.status().equals("PENDING_ACTIVATION"))
            && credential.mustChange() == session.restricted()
            && (session.realm() == LoginRealm.PLATFORM
                || active(tenant)
                    && Objects.equals(session.tenantAuthVersion(), tenant.authVersion()));
    if (expired || !valid) {
      // 返回失败值而不在事务内抛出，确保到期/撤销事实提交后，放宽策略不能复活它。
      if (session.revokedAt() == null) revoke(session.id(), now);
      return new Checked(session, credential, "INVALID_CREDENTIALS");
    }
    store.touch(session.id(), now);
    return new Checked(session, credential, null);
  }

  private static Identity identity(Session session) {
    return new Identity(
        session.id(),
        session.realm(),
        session.tenant(),
        session.user(),
        session.authVersion(),
        session.tenantAuthVersion(),
        session.restricted());
  }

  private void revoke(UUID session, Instant now) {
    store.revoke(session, now);
    authorizations.revokeForSession(session);
  }

  private static Identity principal() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Identity value))
      throw invalid();
    return value;
  }

  private boolean active(Tenant tenant) {
    return tenant != null
        && tenant.status().equals("ACTIVE")
        && (tenant.expiresAt() == null || now().isBefore(tenant.expiresAt()));
  }

  private <T> T attempt(String action, Supplier<T> operation) {
    return attempt(action, null, operation);
  }

  private <T> T attempt(
      String action,
      java.util.concurrent.atomic.AtomicReference<UUID> resolvedTenant,
      Supplier<T> operation) {
    String request = request();
    var context = CallContexts.current();
    try (var ignored =
        CallContexts.bind(
            context == null
                ? new com.ken.platform.foundation.http.CallContext(
                    request, null, null, null, null, null)
                : context)) {
      try {
        return operation.get();
      } catch (RuntimeException failure) {
        String code =
            failure instanceof PlatformException known
                ? known.code()
                : "LOGIN_DEPENDENCY_UNAVAILABLE";
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}"))
          code = "LOGIN_DEPENDENCY_UNAVAILABLE";
        Identity actor = null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!action.equals("identity.password.login")
            && !action.equals("identity.session.browser")
            && authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof Identity candidate) {
          try {
            var bound = store.session(candidate.sessionId(), false);
            if (bound != null && identity(bound).equals(candidate)) actor = candidate;
          } catch (RuntimeException unavailable) {
            /* 无法核实就不冒认失败操作者。 */
          }
        }
        Identity verified = actor;
        String errorCode = code;
        try {
          events.execute(
              status -> {
                audits.failure(
                    verified == null ? null : verified.user(),
                    verified == null ? "ANONYMOUS" : verified.realm().name(),
                    verified == null
                        ? (resolvedTenant == null ? null : resolvedTenant.get())
                        : verified.tenant(),
                    action,
                    verified == null ? null : verified.sessionId(),
                    request,
                    tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString(java.util.Map.of("error_code", errorCode)));
                return null;
              });
        } catch (RuntimeException unavailable) {
          throw new PlatformException("SECURITY_AUDIT_UNAVAILABLE", "安全审计暂不可用", 503);
        }
        if (failure instanceof PlatformException known) throw known;
        throw new PlatformException("LOGIN_DEPENDENCY_UNAVAILABLE", "登录服务暂不可用", 503);
      }
    }
  }

  private static String request() {
    return CallContexts.current() == null
        ? UUID.randomUUID().toString()
        : CallContexts.current().requestId();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void ownBoundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("登录用例必须拥有事务边界");
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "账号或凭证无效", 401);
  }

  private static PlatformException checkError(String code) {
    return code.equals("FIRST_PASSWORD_NOT_REQUIRED")
        ? new PlatformException(code, "当前无需首次改密", 403)
        : invalid();
  }
}
