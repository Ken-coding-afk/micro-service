package com.ken.platform.identity.service.login;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 内部真实 PG/Redis 用例；合成 Spring 主体尚不代表浏览器、Spring Session 或 OIDC 通过。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class LoginLifecycleTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private Integer originalFailureThreshold, originalIdleSeconds;
  private JdbcTransactionManager manager;
  private LoginRedisFixture redis;
  private LoginStore store;
  private PasswordCredentials passwords;
  private SecurityPolicies policies;
  private PasswordLoginService service;
  private JsonNode binding;
  private String url;
  private final UUID a = UUID.randomUUID(),
      b = UUID.randomUUID(),
      userA = UUID.randomUUID(),
      userB = UUID.randomUUID(),
      platform = UUID.randomUUID();
  private final String username = "user-" + UUID.randomUUID();
  private final String codeA = "login-" + a, codeB = "login-" + b;
  private String secretA, secretB, secretPlatform;
  private final String next = "Fresh passphrase " + UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("app_env").asString()).isEqualTo("local");
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    url =
        "jdbc:postgresql://"
            + binding.get("host").asString()
            + ":"
            + binding.get("port").asInt()
            + "/"
            + binding.get("dbname").asString();
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(binding.get("username").asString());
    config.setPassword(Files.readString(Path.of(binding.get("password_file").asString())).strip());
    config.setMaximumPoolSize(5);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    originalFailureThreshold =
        jdbc.queryForObject(
            "SELECT login_failure_threshold FROM sys_security_policy WHERE owner_type='PLATFORM'",
            Integer.class);
    originalIdleSeconds =
        jdbc.queryForObject(
            "SELECT session_idle_seconds FROM sys_security_policy WHERE owner_type='PLATFORM'",
            Integer.class);
    manager = new JdbcTransactionManager(pool);
    var mybatis = new Configuration();
    mybatis.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    mybatis.addMapper(SecurityPolicyMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SecurityPolicyMapper.class);
    passwords = new PasswordCredentials(mapper);
    policies = new SecurityPolicies(mapper);
    for (var pair : List.of(new Object[] {a, codeA}, new Object[] {b, codeB}))
      jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'登录测试')", pair[0], pair[1]);
    var materialA = passwords.temporary(policies.current(a));
    var materialB = passwords.temporary(policies.current(b));
    var materialP = passwords.temporary(policies.current(null));
    secretA = materialA.secret().revealOnce();
    secretB = materialB.secret().revealOnce();
    secretPlatform = materialP.secret().revealOnce();
    data(
        a,
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash) VALUES(?,?,?,'登录测试',?)",
                userA,
                a,
                username,
                materialA.hash()));
    data(
        b,
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash) VALUES(?,?,?,'登录测试',?)",
                userB,
                b,
                username,
                materialB.hash()));
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash) VALUES(?,?,'平台测试',?)",
        platform,
        username,
        materialP.hash());
    redis = new LoginRedisFixture();
    store = new LoginStore(jdbc);
    service = service(store, redis.protection);
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (redis != null) redis.close();
    if (pool != null) {
      try {
        if (originalFailureThreshold != null && originalIdleSeconds != null)
          jdbc.update(
              "UPDATE sys_security_policy SET login_failure_threshold=?,session_idle_seconds=?,version=version+1 WHERE owner_type='PLATFORM'",
              originalFailureThreshold,
              originalIdleSeconds);
      } finally {
        pool.close();
      }
    }
    // 会话/审计/Outbox 与其引用的随机账号留在隔离库中，运行身份没有删除历史的权限。
  }

  private PasswordLoginService service(LoginStore data, LoginProtection protection) {
    return new PasswordLoginService(
        data,
        protection,
        passwords,
        policies,
        new SecurityAuditRepository(jdbc),
        Clock.systemUTC(),
        manager,
        new com.ken.platform.identity.service.oauth.ProtocolAuthorizations(
            jdbc,
            new com.ken.platform.identity.service.oauth.ControlledRegisteredClients(
                new com.ken.platform.identity.dal.mapper.oauth.ClientStore(jdbc)),
            new com.ken.platform.identity.dal.mapper.oauth.ClientStore(jdbc)));
  }

  private <T> T data(UUID tenant, Supplier<T> operation) {
    return new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)", String.class, tenant.toString());
              return operation.get();
            });
  }

  private PasswordLoginService.Identity login() {
    return service.authenticate(
        LoginRealm.TENANT, codeA, username, secretA, InetAddress.getLoopbackAddress());
  }

  private void bind(PasswordLoginService.Identity identity) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
  }

  private PasswordLoginService.Identity activeLogin() {
    data(
        a,
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='ACTIVE',must_change_password=false WHERE id=?",
                userA));
    return login();
  }

  @Test
  void standardProviderAcceptsLegacyCostButRejectsWrongPasswordWithoutSession() {
    String legacy =
        "{bcrypt}"
            + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                .encode(secretA);
    data(a, () -> jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", legacy, userA));
    var identity = login();
    assertThat(identity.user()).isEqualTo(userA);
    assertThat(identity.tenant()).isEqualTo(a);
    assertThat(identity.restricted()).isTrue();
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeA,
                username,
                "Incorrect password",
                InetAddress.getLoopbackAddress()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_login_session WHERE user_id=?", Integer.class, userA))
        .isEqualTo(1);
  }

  @Test
  void ownSessionPageContainsOnlyThisAccountAndNeverRedisOrCredentialSecrets() throws Exception {
    var first = activeLogin();
    var second = login();
    var foreign =
        service.authenticate(
            LoginRealm.TENANT, codeB, username, secretB, InetAddress.getLoopbackAddress());
    bind(first);
    service.bindRedisSession("DO_NOT_EXPOSE_SESSION_REDIS");
    var page = service.ownSessions(0, 100);
    assertThat(page)
        .extracting(PasswordLoginService.SessionSummary::id)
        .containsExactlyInAnyOrder(first.sessionId(), second.sessionId())
        .doesNotContain(foreign.sessionId());
    assertThat(page).filteredOn(PasswordLoginService.SessionSummary::current).hasSize(1);
    assertThat(page).allMatch(PasswordLoginService.SessionSummary::active);
    assertThat(service.ownSessions(0, 1)).hasSize(1);
    assertThat(service.ownSessions(2, 1)).isEmpty();
    String serialized = JsonMapper.builder().build().writeValueAsString(page);
    assertThat(serialized)
        .doesNotContain(
            "DO_NOT_EXPOSE_SESSION_REDIS", "password", "authVersion", "redisSessionId", secretA);
    rejected("INVALID_PAGINATION", 400, () -> service.ownSessions(-1, 1));
    rejected("INVALID_PAGINATION", 400, () -> service.ownSessions(0, 101));
  }

  @Test
  void ownSessionRevokeIsIdempotentAndPreservesOtherSessionAndCleanupIntent() {
    var owner = activeLogin();
    var target = login();
    bind(target);
    service.bindRedisSession("own-session-cleanup-" + UUID.randomUUID());
    bind(owner);
    service.revokeOwnSession(target.sessionId());
    service.revokeOwnSession(target.sessionId());
    assertThat(store.session(target.sessionId(), false).revokedAt()).isNotNull();
    assertThat(store.session(target.sessionId(), false).cleanupPending()).isTrue();
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNull();
    assertThat(service.requireBusinessSession()).isEqualTo(owner);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.session.revoked' AND result='SUCCESS' AND target_id=?",
                Integer.class,
                target.sessionId().toString()))
        .isEqualTo(1);
    bind(target);
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
  }

  @Test
  void sessionRevokeRejectsForeignTenantForeignAccountAndAnonymousWithoutChangingThem() {
    var owner = activeLogin();
    var foreign =
        service.authenticate(
            LoginRealm.TENANT, codeB, username, secretB, InetAddress.getLoopbackAddress());
    var otherUser = UUID.randomUUID();
    data(
        a,
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) SELECT ?,tenant_id,?,'另一个账号',password_hash,'ACTIVE',false FROM sys_user WHERE id=?",
                otherUser,
                "other-" + otherUser,
                userA));
    var other =
        service.authenticate(
            LoginRealm.TENANT,
            codeA,
            "other-" + otherUser,
            secretA,
            InetAddress.getLoopbackAddress());
    bind(owner);
    for (UUID id : List.of(foreign.sessionId(), other.sessionId(), UUID.randomUUID()))
      rejected("SESSION_NOT_FOUND", 404, () -> service.revokeOwnSession(id));
    assertThat(store.session(foreign.sessionId(), false).revokedAt()).isNull();
    assertThat(store.session(other.sessionId(), false).revokedAt()).isNull();
    SecurityContextHolder.clearContext();
    rejected("INVALID_CREDENTIALS", 401, () -> service.revokeOwnSession(owner.sessionId()));
  }

  @Test
  void allOwnSessionsIncrementVersionOnceAndCannotRevokeAnotherTenant() {
    var first = activeLogin();
    var second = login();
    var foreign =
        service.authenticate(
            LoginRealm.TENANT, codeB, username, secretB, InetAddress.getLoopbackAddress());
    bind(first);
    assertThat(service.signOutAllOwnSessions()).isEqualTo(2);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(store.session(first.sessionId(), false).revokedAt()).isNotNull();
    assertThat(store.session(second.sessionId(), false).revokedAt()).isNotNull();
    assertThat(store.session(foreign.sessionId(), false).revokedAt()).isNull();
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isEqualTo(1);
    bind(second);
    rejected("INVALID_CREDENTIALS", 401, () -> service.signOutAllOwnSessions());
    var fresh = login();
    assertThat(fresh.authVersion()).isEqualTo(1);
    bind(fresh);
    assertThat(service.requireBusinessSession()).isEqualTo(fresh);
  }

  @Test
  void pendingOrExpiredBindingCannotManageSessionsAndExpiryRemainsCommitted() {
    bind(login());
    rejected("PASSWORD_CHANGE_REQUIRED", 403, () -> service.ownSessions(0, 100));
    rejected("PASSWORD_CHANGE_REQUIRED", 403, () -> service.signOutAllOwnSessions());
    var owner = activeLogin();
    bind(owner);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '9 hours',last_activity_at=now()-interval '9 hours' WHERE id=?",
        owner.sessionId());
    rejected("INVALID_CREDENTIALS", 401, () -> service.ownSessions(0, 100));
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNotNull();
  }

  @Test
  void allSessionSuccessAuditFailureRollsBackVersionsRevocationsAndLeavesFailureEvent() {
    var owner = activeLogin();
    var second = login();
    bind(owner);
    var broken = spy(store);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_SESSION_AUDIT"))
        .when(broken)
        .audit(any(), eq("identity.sessions.signed_out_all"), anyString());
    rejected(
        "LOGIN_DEPENDENCY_UNAVAILABLE",
        503,
        () -> service(broken, redis.protection).signOutAllOwnSessions());
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNull();
    assertThat(store.session(second.sessionId(), false).revokedAt()).isNull();
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.sessions.signed_out_all' AND result='FAILURE'",
                Integer.class,
                userA))
        .isEqualTo(1);
    assertThat(service.signOutAllOwnSessions()).isEqualTo(2);
  }

  @Test
  void concurrentOwnSessionRevokeCommitsOneSuccessAuditAndBothCallsFinish() throws Exception {
    var owner = activeLogin();
    var target = login();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            executor.submit(
                () -> {
                  bind(owner);
                  try {
                    service.revokeOwnSession(target.sessionId());
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.session.revoked' AND result='SUCCESS' AND target_id=?",
                Integer.class,
                target.sessionId().toString()))
        .isEqualTo(1);
  }

  @Test
  void ownPasswordChangeValidatesOldSecretPolicyAndRevokesAllOwnSessionsOnly() {
    var first = activeLogin();
    var second = login();
    var foreign =
        service.authenticate(
            LoginRealm.TENANT, codeB, username, secretB, InetAddress.getLoopbackAddress());
    bind(first);
    rejected("CURRENT_PASSWORD_INVALID", 400, () -> service.changeOwnPassword("incorrect", next));
    rejected("PASSWORD_REUSE", 400, () -> service.changeOwnPassword(secretA, secretA));
    rejected("PASSWORD_REJECTED", 400, () -> service.changeOwnPassword(secretA, "short"));
    assertThat(store.session(first.sessionId(), false).revokedAt()).isNull();
    service.changeOwnPassword(secretA, next);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(store.session(first.sessionId(), false).revokedAt()).isNotNull();
    assertThat(store.session(second.sessionId(), false).revokedAt()).isNotNull();
    assertThat(store.session(foreign.sessionId(), false).revokedAt()).isNull();
    rejected("INVALID_CREDENTIALS", 401, () -> login());
    var fresh =
        service.authenticate(
            LoginRealm.TENANT, codeA, username, next, InetAddress.getLoopbackAddress());
    assertThat(fresh.authVersion()).isEqualTo(1);
    bind(fresh);
    assertThat(service.requireBusinessSession()).isEqualTo(fresh);
  }

  @Test
  void ownPasswordRequiresRecentAuthenticationWithoutDestroyingTheStillValidSso() {
    var owner = activeLogin();
    bind(owner);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '301 seconds' WHERE id=?",
        owner.sessionId());
    rejected("RECENT_AUTHENTICATION_REQUIRED", 403, () -> service.changeOwnPassword(secretA, next));
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNull();
    assertThat(service.requireBusinessSession()).isEqualTo(owner);
    var fresh = login();
    bind(fresh);
    service.changeOwnPassword(secretA, next);
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNotNull();
  }

  @Test
  void ownPasswordAuditFailureRollsBackHashVersionAndAllBindingsWithoutRecordingSecrets() {
    var owner = activeLogin();
    var second = login();
    bind(owner);
    String before =
        data(
            a,
            () ->
                jdbc.queryForObject(
                    "SELECT password_hash FROM sys_user WHERE id=?", String.class, userA));
    var broken = spy(store);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_PASSWORD_WRITE"))
        .when(broken)
        .audit(any(), eq("identity.password.changed"), anyString());
    rejected(
        "LOGIN_DEPENDENCY_UNAVAILABLE",
        503,
        () -> service(broken, redis.protection).changeOwnPassword(secretA, next));
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE id=?", String.class, userA)))
        .isEqualTo(before);
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isZero();
    assertThat(store.session(owner.sessionId(), false).revokedAt()).isNull();
    assertThat(store.session(second.sessionId(), false).revokedAt()).isNull();
    var details =
        jdbc.queryForList(
            "SELECT details::text FROM sys_auth_audit WHERE actor_id=? AND action='identity.password.changed'",
            String.class,
            userA);
    assertThat(details)
        .hasSize(1)
        .allSatisfy(
            value ->
                assertThat(value)
                    .doesNotContain(secretA, next, before, "DO_NOT_ECHO_PASSWORD_WRITE"));
  }

  @Test
  void anonymousPendingAndPlatformPasswordChangesRespectTheirOriginalRealm() {
    rejected("INVALID_CREDENTIALS", 401, () -> service.changeOwnPassword(secretA, next));
    bind(login());
    rejected("PASSWORD_CHANGE_REQUIRED", 403, () -> service.changeOwnPassword(secretA, next));
    jdbc.update("UPDATE sys_platform_user SET must_change_password=false WHERE id=?", platform);
    var owner =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, secretPlatform, InetAddress.getLoopbackAddress());
    bind(owner);
    service.changeOwnPassword(secretPlatform, next);
    assertThat(
            jdbc.queryForObject(
                "SELECT auth_version FROM sys_platform_user WHERE id=?", Long.class, platform))
        .isEqualTo(1);
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isZero();
    var fresh =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, next, InetAddress.getLoopbackAddress());
    assertThat(fresh.realm()).isEqualTo(LoginRealm.PLATFORM);
    assertThat(fresh.tenant()).isNull();
  }

  @Test
  void concurrentAllSessionExitHasOneWinnerAndOnlyOneVersionIncrement() throws Exception {
    var first = activeLogin();
    var second = login();
    var winners = new AtomicInteger();
    var rejected = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks =
          List.of(first, second).stream()
              .map(
                  identity ->
                      executor.submit(
                          () -> {
                            bind(identity);
                            try {
                              assertThat(service.signOutAllOwnSessions()).isEqualTo(2);
                              winners.incrementAndGet();
                            } catch (PlatformException failure) {
                              assertThat(failure.code()).isEqualTo("INVALID_CREDENTIALS");
                              rejected.incrementAndGet();
                            } finally {
                              SecurityContextHolder.clearContext();
                            }
                          }))
              .toList();
      for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(winners.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(1);
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isEqualTo(1);
  }

  @Test
  void platformAllSessionExitNeverSelectsTenantSessionsWithTheSameUsername() {
    var tenantIdentity = activeLogin();
    jdbc.update("UPDATE sys_platform_user SET must_change_password=false WHERE id=?", platform);
    var owner =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, secretPlatform, InetAddress.getLoopbackAddress());
    bind(owner);
    assertThat(service.ownSessions(0, 100))
        .extracting(PasswordLoginService.SessionSummary::id)
        .containsExactly(owner.sessionId());
    assertThat(service.signOutAllOwnSessions()).isEqualTo(1);
    assertThat(store.session(tenantIdentity.sessionId(), false).revokedAt()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT auth_version FROM sys_platform_user WHERE id=?", Long.class, platform))
        .isEqualTo(1);
  }

  private void rejected(
      String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            PlatformException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.status()).isEqualTo(status);
            });
  }

  private long activationCount() throws Exception {
    String owner = binding.get("dbname").asString() + "_owner";
    assertThat(owner).matches("[a-z][a-z0-9_]*");
    // 独立 migrator/owner 只读观察连接，不扩大实际应用 runtime 的 Outbox 权限。
    try (var connection =
        DriverManager.getConnection(
            url,
            binding.get("observer_username").asString(),
            Files.readString(Path.of(binding.get("observer_password_file").asString())).strip())) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL ROLE \"" + owner + "\"");
      }
      try (var statement =
          connection.prepareStatement(
              "SELECT count(*) FROM identity_technical.outbox WHERE aggregate_id=? AND event_type='identity.user.activated.v1'")) {
        statement.setObject(1, userA);
        try (var rows = statement.executeQuery()) {
          rows.next();
          long count = rows.getLong(1);
          if (count > 0) {
            try (var payload =
                connection.prepareStatement(
                    "SELECT data::text,schema_version,tenant_id,aggregate_version FROM identity_technical.outbox WHERE aggregate_id=? AND event_type='identity.user.activated.v1'")) {
              payload.setObject(1, userA);
              try (var events = payload.executeQuery()) {
                assertThat(events.next()).isTrue();
                var data = JsonMapper.builder().build().readTree(events.getString(1));
                assertThat(data.size()).isEqualTo(2);
                assertThat(data.get("user_id").asString()).isEqualTo(userA.toString());
                assertThat(data.get("user_version").asLong()).isEqualTo(events.getLong(4));
                assertThat(events.getInt(2)).isEqualTo(1);
                assertThat(events.getObject(3, UUID.class)).isEqualTo(a);
              }
            }
          }
          return count;
        }
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void tenantAndPlatformSameNamesRemainSeparateAndPendingLoginCannotAuthorizeBusiness() {
    var ia = login();
    var ib =
        service.authenticate(
            LoginRealm.TENANT,
            codeB,
            "  " + username.toUpperCase(java.util.Locale.ROOT) + "  ",
            secretB,
            InetAddress.getLoopbackAddress());
    var ip =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, secretPlatform, InetAddress.getLoopbackAddress());
    assertThat(ia.user()).isEqualTo(userA);
    assertThat(ib.user()).isEqualTo(userB);
    assertThat(ip.user()).isEqualTo(platform);
    assertThat(ia.restricted()).isTrue();
    assertThat(ip.tenant()).isNull();
    bind(ia);
    rejected("PASSWORD_CHANGE_REQUIRED", 403, () -> service.requireBusinessSession());
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.TENANT, codeB, username, secretA, InetAddress.getLoopbackAddress()));
  }

  @Test
  void firstPasswordActivatesOnceAndRequiresFreshLoginWithTheNewPassword() throws Exception {
    var original = login();
    bind(original);
    assertThat(activationCount()).isZero();
    service.changeInitialPassword(next);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(store.session(original.sessionId(), false).revokedAt()).isNotNull();
    assertThat(activationCount()).isEqualTo(1);
    rejected("INVALID_CREDENTIALS", 401, () -> login());
    var fresh =
        service.authenticate(
            LoginRealm.TENANT, codeA, username, next, InetAddress.getLoopbackAddress());
    assertThat(fresh.restricted()).isFalse();
    assertThat(fresh.authVersion()).isEqualTo(1);
    bind(fresh);
    assertThat(service.requireBusinessSession().sessionId()).isEqualTo(fresh.sessionId());
    bind(original);
    rejected("INVALID_CREDENTIALS", 401, () -> service.changeInitialPassword(next));
    assertThat(activationCount()).isEqualTo(1);
  }

  @Test
  void weakAndUnchangedPasswordsDoNotActivateOrChangeTheBinding() throws Exception {
    var identity = login();
    bind(identity);
    rejected("PASSWORD_REJECTED", 400, () -> service.changeInitialPassword("short"));
    rejected("PASSWORD_REUSE", 400, () -> service.changeInitialPassword(secretA));
    assertThat(activationCount()).isZero();
    assertThat(store.session(identity.sessionId(), false).revokedAt()).isNull();
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT must_change_password FROM sys_user WHERE id=?",
                        Boolean.class,
                        userA)))
        .isTrue();
  }

  @Test
  void successAuditFailureRollsBackPasswordActivationOutboxAndRevocation() throws Exception {
    var identity = login();
    bind(identity);
    var broken = spy(store);
    doThrow(new DataAccessResourceFailureException("secret SQL fixture"))
        .when(broken)
        .audit(any(), eq("identity.password.initial"), anyString());
    rejected(
        "LOGIN_DEPENDENCY_UNAVAILABLE",
        503,
        () -> service(broken, redis.protection).changeInitialPassword(next));
    assertThat(activationCount()).isZero();
    assertThat(store.session(identity.sessionId(), false).revokedAt()).isNull();
    assertThat(
            data(
                a,
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, userA)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.password.initial' AND result='FAILURE' AND actor_id=? AND tenant_id=? AND target_id=?",
                Integer.class,
                userA,
                a,
                identity.sessionId().toString()))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void disabledAndExpiredTenantCannotAuthenticateAndRestoringUserDoesNotReviveOldBinding() {
    jdbc.update("UPDATE sys_tenant SET status='DISABLED' WHERE id=?", a);
    rejected("INVALID_CREDENTIALS", 401, () -> login());
    jdbc.update(
        "UPDATE sys_tenant SET status='ACTIVE',expires_at=now()-interval '1 second' WHERE id=?", a);
    rejected("INVALID_CREDENTIALS", 401, () -> login());
    jdbc.update("UPDATE sys_tenant SET expires_at=NULL WHERE id=?", a);
    var identity = login();
    bind(identity);
    data(
        a,
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                userA));
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
    data(a, () -> jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=?", userA));
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
    assertThat(store.session(identity.sessionId(), false).revokedAt()).isNotNull();
  }

  @Test
  void policyTighteningExpiresSessionPermanentlyAndRecentAuthenticationBoundsInitialChange() {
    data(
        a,
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='ACTIVE',must_change_password=false WHERE id=?",
                userA));
    var ordinary = login();
    bind(ordinary);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '100 seconds',last_activity_at=now()-interval '100 seconds' WHERE id=?",
        ordinary.sessionId());
    jdbc.update(
        "UPDATE sys_security_policy SET session_idle_seconds=60,version=version+1 WHERE owner_type='PLATFORM'");
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
    jdbc.update(
        "UPDATE sys_security_policy SET session_idle_seconds=1800,version=version+1 WHERE owner_type='PLATFORM'");
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
    data(
        a,
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='PENDING_ACTIVATION',must_change_password=true WHERE id=?",
                userA));
    var limited = login();
    bind(limited);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '301 seconds',last_activity_at=now()-interval '301 seconds' WHERE id=?",
        limited.sessionId());
    rejected("INVALID_CREDENTIALS", 401, () -> service.changeInitialPassword(next));
    assertThat(store.session(limited.sessionId(), false).revokedAt()).isNotNull();
  }

  @Test
  void concurrentFirstChangeCommitsExactlyOneActivation() throws Exception {
    var first = login();
    var second = login();
    AtomicInteger completed = new AtomicInteger(), rejected = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(first, second).stream()
              .map(
                  identity ->
                      executor.submit(
                          () -> {
                            bind(identity);
                            try {
                              service.changeInitialPassword(next);
                              completed.incrementAndGet();
                            } catch (PlatformException failure) {
                              assertThat(failure.code()).isEqualTo("INVALID_CREDENTIALS");
                              rejected.incrementAndGet();
                            } finally {
                              SecurityContextHolder.clearContext();
                            }
                          }))
              .toList();
      for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(completed.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(1);
    assertThat(activationCount()).isEqualTo(1);
  }

  @Test
  void forgedRealmOrTenantDoesNotSelectAnotherBindingAndAnonymousCannotChangePassword() {
    var original = login();
    bind(
        new PasswordLoginService.Identity(
            original.sessionId(),
            LoginRealm.TENANT,
            b,
            original.user(),
            original.authVersion(),
            original.tenantAuthVersion(),
            original.restricted()));
    rejected("INVALID_CREDENTIALS", 401, () -> service.requireBusinessSession());
    assertThat(store.session(original.sessionId(), false).revokedAt()).isNull();
    SecurityContextHolder.clearContext();
    rejected("INVALID_CREDENTIALS", 401, () -> service.changeInitialPassword(next));
  }

  @Test
  void failedRedisCompletionRevokesTheCommittedButUndeliveredBinding() {
    var broken = spy(redis.protection);
    doThrow(new PlatformException("LOGIN_PROTECTION_UNAVAILABLE", "fixture", 503))
        .when(broken)
        .success(any(), any());
    rejected(
        "LOGIN_PROTECTION_UNAVAILABLE",
        503,
        () ->
            service(store, broken)
                .authenticate(
                    LoginRealm.TENANT, codeA, username, secretA, InetAddress.getLoopbackAddress()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_login_session WHERE user_id=? AND revoked_at IS NOT NULL",
                Integer.class,
                userA))
        .isEqualTo(1);
  }

  @Test
  void platformInitialPasswordUsesIndependentAccountAndNoTenantIdentity() {
    var original =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, secretPlatform, InetAddress.getLoopbackAddress());
    bind(original);
    service.changeInitialPassword(next);
    var fresh =
        service.authenticate(
            LoginRealm.PLATFORM, null, username, next, InetAddress.getLoopbackAddress());
    bind(fresh);
    assertThat(fresh.tenant()).isNull();
    assertThat(fresh.realm()).isEqualTo(LoginRealm.PLATFORM);
    assertThat(fresh.authVersion()).isEqualTo(1);
    assertThat(service.requireBusinessSession().user()).isEqualTo(platform);
    assertThat(store.session(original.sessionId(), false).revokedAt()).isNotNull();
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.PLATFORM, codeA, username, next, InetAddress.getLoopbackAddress()));
  }

  @Test
  void activityTimestampDoesNotMoveBackward() {
    data(
        a,
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='ACTIVE',must_change_password=false WHERE id=?",
                userA));
    var identity = login();
    bind(identity);
    jdbc.update(
        "UPDATE sys_login_session SET last_activity_at=now()+interval '5 seconds' WHERE id=?",
        identity.sessionId());
    var before = store.session(identity.sessionId(), false).lastActivity();
    service.requireBusinessSession();
    assertThat(store.session(identity.sessionId(), false).lastActivity()).isEqualTo(before);
  }

  @Test
  void nonexistentAccountHasSameThresholdAndLockAsIncorrectExistingCredentials() {
    jdbc.update(
        "UPDATE sys_security_policy SET login_failure_threshold=2,version=version+1 WHERE owner_type='PLATFORM'");
    String missing = "missing-" + UUID.randomUUID();
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeA,
                missing,
                "incorrect-password",
                InetAddress.getLoopbackAddress()));
    rejected(
        "LOGIN_RATE_LIMITED",
        429,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeA,
                missing,
                "incorrect-password",
                InetAddress.getLoopbackAddress()));
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeA,
                username,
                "incorrect-password",
                InetAddress.getLoopbackAddress()));
    rejected(
        "LOGIN_RATE_LIMITED",
        429,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeA,
                username,
                "incorrect-password",
                InetAddress.getLoopbackAddress()));
    rejected(
        "INVALID_CREDENTIALS",
        401,
        () ->
            service.authenticate(
                LoginRealm.TENANT,
                codeB,
                missing,
                "incorrect-password",
                InetAddress.getLoopbackAddress()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.password.login' AND result='FAILURE' AND tenant_id=? AND actor_type='ANONYMOUS' AND actor_id IS NULL",
                Integer.class,
                a))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_login_session WHERE tenant_id=?", Integer.class, a))
        .isZero();
  }
}
