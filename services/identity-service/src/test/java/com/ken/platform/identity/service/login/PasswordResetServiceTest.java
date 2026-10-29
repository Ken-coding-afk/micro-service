package com.ken.platform.identity.service.login;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.*;
import com.ken.platform.foundation.http.*;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.*;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.*;
import com.ken.platform.identity.service.oauth.*;
import com.ken.platform.identity.service.security.*;
import com.ken.platform.identity.service.user.UserProfileService;
import com.zaxxer.hikari.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import tools.jackson.databind.json.JsonMapper;

/** 真实 PG/Redis 验证签发、消费、隔离与事务；HTTP/浏览器流程另行验证。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class PasswordResetServiceTest {
  HikariDataSource pool;
  JdbcTemplate jdbc;
  JdbcTransactionManager manager;
  IdentityTenantScope scope;
  TenantManagementAuthorization authorization;
  TenantTransactions tenants;
  ManagementAudits audits;
  UserManagementMapper users;
  AccountManagementMapper accounts;
  PasswordCredentials passwords;
  SecurityPolicies policies;
  LoginRedisFixture redis;
  LoginStore sessions;
  PasswordResetStore tickets;
  ProtocolAuthorizations protocols;
  Clock clock = Clock.systemUTC();
  final UUID tenant = UUID.randomUUID(),
      other = UUID.randomUUID(),
      user = UUID.randomUUID(),
      otherUser = UUID.randomUUID(),
      actor = UUID.randomUUID();
  final String original = "Old password " + UUID.randomUUID(),
      next = "New password " + UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    var b =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(b.get("test_only").asBoolean()).isTrue();
    assertThat(b.get("app_env").asString()).isEqualTo("local");
    assertThat(b.get("dbname").asString()).startsWith("t02t");
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + b.get("host").asString()
            + ":"
            + b.get("port").asInt()
            + "/"
            + b.get("dbname").asString());
    config.setUsername(b.get("username").asString());
    config.setPassword(Files.readString(Path.of(b.get("password_file").asString())).strip());
    config.setMaximumPoolSize(6);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    manager = new JdbcTransactionManager(pool);
    scope = new IdentityTenantScope(jdbc, manager);
    tenants = new TenantTransactions(jdbc, manager);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    for (var type :
        List.of(
            UserManagementMapper.class, AccountManagementMapper.class, SecurityPolicyMapper.class))
      configuration.addMapper(type);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var sql = new SqlSessionTemplate(factory.getObject());
    users = sql.getMapper(UserManagementMapper.class);
    accounts = sql.getMapper(AccountManagementMapper.class);
    authorization =
        new TenantManagementAuthorization(
            users, new PlatformManagementAuthorization(accounts), scope, manager);
    audits = new ManagementAudits(new SecurityAuditRepository(jdbc), manager);
    var policyMapper = sql.getMapper(SecurityPolicyMapper.class);
    passwords = new PasswordCredentials(policyMapper);
    policies = new SecurityPolicies(policyMapper);
    redis = new LoginRedisFixture();
    sessions = new LoginStore(jdbc, scope);
    tickets = new PasswordResetStore(jdbc);
    var clients = new ClientStore(jdbc);
    protocols = new ProtocolAuthorizations(jdbc, new ControlledRegisteredClients(clients), clients);
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,'测试管理员','unused','ACTIVE',false)",
        actor,
        "p-" + actor);
    for (UUID id : List.of(tenant, other)) {
      jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'重置测试')", id, "reset-" + id);
      data(
          id,
          () ->
              jdbc.update(
                  "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'测试用户',?,'ACTIVE',false)",
                  id.equals(tenant) ? user : otherUser,
                  id,
                  "u-" + id,
                  passwords.encode(original, policies.current(id))));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class))
        .isFalse();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
    if (redis != null) redis.close();
    if (pool != null) pool.close();
  }

  private PasswordResetService service() {
    return new PasswordResetService(
        tickets,
        authorization,
        users,
        accounts,
        tenants,
        scope,
        manager,
        passwords,
        policies,
        new RecentAuthentication(policies, clock),
        redis.protection,
        sessions,
        protocols,
        audits,
        clock);
  }

  private <T> T data(UUID id, Supplier<T> action) {
    return new TransactionTemplate(manager).execute(status -> scope.within(id, action));
  }

  private <T> T platform(
      UUID target,
      String request,
      List<String> permissions,
      Instant authenticated,
      String client,
      Supplier<T> action) {
    var authorities =
        permissions.stream()
            .<org.springframework.security.core.GrantedAuthority>map(
                v -> new SimpleGrantedAuthority("PERMISSION_" + v))
            .toList();
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            Map.of(
                "principal_type",
                "platform",
                "client_id",
                client,
                UserTokenService.AUTHENTICATED_AT,
                authenticated),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    var servlet = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servlet));
    try (var ignored =
        CallContexts.bind(new CallContext(request, null, actor.toString(), null, null, null))) {
      authorization.selectTarget(servlet, target);
      T result = action.get();
      assertThat(CallContexts.current().tenantId()).isNull();
      assertThat(principal.<String>getAttribute("principal_type")).isEqualTo("platform");
      return result;
    } finally {
      RequestContextHolder.resetRequestAttributes();
      SecurityContextHolder.clearContext();
    }
  }

  private <T> T platform(Supplier<T> action) {
    return platform(
        tenant,
        UUID.randomUUID().toString(),
        List.of("platform:identity:read", "platform:identity:manage"),
        clock.instant(),
        "identity-admin-platform",
        action);
  }

  private PasswordResetService.Issued issue() {
    return platform(() -> service().issue(user, 0, InetAddress.getLoopbackAddress()));
  }

  private void consume(String token) {
    service().consume(token, next, next, InetAddress.getLoopbackAddress());
  }

  private void code(Runnable operation, String expected) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  @Test
  void consumesOnceChangesPasswordAndRevokesOldSessions() {
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    data(
        tenant,
        () -> {
          sessions.createSession(
              new LoginStore.Session(
                  session,
                  LoginRealm.TENANT,
                  tenant,
                  user,
                  0,
                  0L,
                  false,
                  now,
                  now,
                  null,
                  null,
                  false));
          return null;
        });
    var issued = issue();
    assertThat(issued.expiresAt())
        .isBetween(now.plusSeconds(899), clock.instant().plusSeconds(901));
    assertThat(issued.toString()).doesNotContain(issued.resetToken());
    consume(issued.resetToken());
    data(
        tenant,
        () -> {
          assertThat(passwords.matches(next, tickets.password(tenant, user))).isTrue();
          assertThat(passwords.matches(original, tickets.password(tenant, user))).isFalse();
          assertThat(users.lockUsers(tenant, List.of(user)).getFirst().authVersion()).isEqualTo(1);
          return null;
        });
    assertThat(sessions.session(session, false).revokedAt()).isNotNull();
    code(() -> consume(issued.resetToken()), "PASSWORD_RESET_INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_type FROM sys_auth_audit WHERE target_id=? AND action='identity.password-reset.issued' AND result='SUCCESS'",
                String.class,
                user.toString()))
        .isEqualTo("PLATFORM");
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM sys_auth_audit WHERE target_id=? AND action='identity.password-reset.issued' AND result='SUCCESS'",
                UUID.class,
                user.toString()))
        .isEqualTo(tenant);
    assertThat(
            jdbc.queryForObject(
                "SELECT string_agg(details::text,' ') FROM sys_auth_audit WHERE target_id=?",
                String.class,
                user.toString()))
        .doesNotContain(issued.resetToken())
        .doesNotContain(next);
  }

  @Test
  void expiryAndReplacementRejectOldTicketsWithoutChangingPassword() {
    var first = issue();
    var second = issue();
    code(() -> consume(first.resetToken()), "PASSWORD_RESET_INVALID");
    clock = Clock.offset(clock, Duration.ofMinutes(16));
    code(() -> consume(second.resetToken()), "PASSWORD_RESET_INVALID");
    assertThat(data(tenant, () -> passwords.matches(original, tickets.password(tenant, user))))
        .isTrue();
  }

  @Test
  void invalidPasswordDoesNotConsumeTicket() {
    var issued = issue();
    code(
        () ->
            service()
                .consume(issued.resetToken(), next, "different", InetAddress.getLoopbackAddress()),
        "PASSWORD_CONFIRM_MISMATCH");
    code(
        () ->
            service()
                .consume(issued.resetToken(), original, original, InetAddress.getLoopbackAddress()),
        "PASSWORD_REUSE");
    consume(issued.resetToken());
  }

  @Test
  void concurrentConsumersHaveExactlyOneCommit() throws Exception {
    var issued = issue();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> operation =
          () -> {
            barrier.await();
            try {
              consume(issued.resetToken());
              return true;
            } catch (PlatformException e) {
              assertThat(e.code()).isEqualTo("PASSWORD_RESET_INVALID");
              return false;
            }
          };
      var a = executor.submit(operation);
      var b = executor.submit(operation);
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(data(tenant, () -> users.lockUsers(tenant, List.of(user)).getFirst().authVersion()))
        .isEqualTo(1);
  }

  @Test
  void issuerRequiresRecentAuthenticationAndManageScope() {
    code(
        () ->
            platform(
                tenant,
                UUID.randomUUID().toString(),
                List.of("platform:identity:read"),
                clock.instant(),
                "identity-admin-platform",
                () -> service().issue(user, 0, InetAddress.getLoopbackAddress())),
        "FORBIDDEN");
    code(
        () ->
            platform(
                tenant,
                UUID.randomUUID().toString(),
                List.of("platform:identity:read", "platform:identity:manage"),
                Instant.EPOCH,
                "identity-admin-platform",
                () -> service().issue(user, 0, InetAddress.getLoopbackAddress())),
        "RECENT_AUTHENTICATION_REQUIRED");
    assertThat(
            platform(
                tenant,
                UUID.randomUUID().toString(),
                List.of("platform:identity:read", "platform:identity:manage"),
                clock.instant(),
                "another-client",
                () -> authorization.actorFor("identity:user:manage")))
        .isEqualTo(actor);
    code(
        () -> platform(() -> service().issue(otherUser, 0, InetAddress.getLoopbackAddress())),
        "NOT_FOUND");
    code(
        () -> platform(() -> service().issue(user, 99, InetAddress.getLoopbackAddress())),
        "USER_VERSION_CONFLICT");
  }

  @Test
  void repeatedIssuanceRequestNeverRevealsOrReplacesCredential() {
    String request = UUID.randomUUID().toString();
    Supplier<PasswordResetService.Issued> command =
        () ->
            platform(
                tenant,
                request,
                List.of("platform:identity:read", "platform:identity:manage"),
                clock.instant(),
                "identity-admin-platform",
                () -> service().issue(user, 0, InetAddress.getLoopbackAddress()));
    var issued = command.get();
    code(command::get, "PASSWORD_RESET_ALREADY_ISSUED");
    consume(issued.resetToken());
  }

  @Test
  void platformTargetIsolationAndDisabledActorAreEnforcedInTransaction() {
    var profiles = new UserProfileService(tenants, users, authorization, audits, clock);
    platform(
        () ->
            profiles.update(
                user, 0, new UserProfileService.Fields("已更新", null, null, null, null, null, null)));
    code(
        () ->
            platform(
                () ->
                    profiles.update(
                        otherUser,
                        1,
                        new UserProfileService.Fields("越界", null, null, null, null, null, null))),
        "NOT_FOUND");
    assertThat(data(other, () -> users.displayName(other, otherUser))).isEqualTo("测试用户");
    code(
        () ->
            platform(
                () ->
                    profiles.update(
                        user,
                        0,
                        new UserProfileService.Fields("旧版本", null, null, null, null, null, null))),
        "USER_VERSION_CONFLICT");
    jdbc.update("UPDATE sys_platform_user SET status='DISABLED' WHERE id=?", actor);
    code(
        () ->
            platform(
                () ->
                    profiles.update(
                        user,
                        1,
                        new UserProfileService.Fields("停用后", null, null, null, null, null, null))),
        "INVALID_CREDENTIALS");
    assertThat(data(tenant, () -> users.displayName(tenant, user))).isEqualTo("已更新");
    assertThat(jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class))
        .isIn(null, "");
  }

  @Test
  void concurrentPlatformDisableCommitsBeforeWaitingTenantWrite() throws Exception {
    var profiles = new UserProfileService(tenants, users, authorization, audits, clock);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var disable =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .execute(
                          status -> {
                            users.lockCatalog();
                            jdbc.queryForObject(
                                "SELECT id FROM sys_platform_user WHERE id=? FOR UPDATE",
                                UUID.class,
                                actor);
                            jdbc.update(
                                "UPDATE sys_platform_user SET status='DISABLED' WHERE id=?", actor);
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("release timeout");
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(interrupted);
                            }
                            return null;
                          }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      var change =
          executor.submit(
              () ->
                  platform(
                      () ->
                          profiles.update(
                              user,
                              0,
                              new UserProfileService.Fields(
                                  "不得提交", null, null, null, null, null, null))));
      release.countDown();
      disable.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> change.get(15, TimeUnit.SECONDS))
          .hasCauseInstanceOf(PlatformException.class)
          .satisfies(
              failure ->
                  assertThat(((PlatformException) failure.getCause()).code())
                      .isEqualTo("INVALID_CREDENTIALS"));
    } finally {
      release.countDown();
    }
    assertThat(data(tenant, () -> users.displayName(tenant, user))).isEqualTo("测试用户");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND tenant_id=? AND result<>'SUCCESS'",
                Integer.class,
                actor,
                tenant))
        .isPositive();
  }

  @Test
  void repeatedInvalidCredentialIsRateLimitedWithoutPasswordMutation() {
    for (int attempt = 0; attempt < policies.current(null).loginFailureThreshold() - 1; attempt++) {
      code(() -> consume("invalid-reset-ticket"), "PASSWORD_RESET_INVALID");
    }
    code(() -> consume("invalid-reset-ticket"), "LOGIN_RATE_LIMITED");
    assertThat(data(tenant, () -> passwords.matches(original, tickets.password(tenant, user))))
        .isTrue();
  }
}
