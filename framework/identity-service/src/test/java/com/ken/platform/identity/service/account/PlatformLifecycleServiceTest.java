package com.ken.platform.identity.service.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.account.PlatformLifecycleMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.account.PlatformLifecycleService.Status;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 仅验证真实数据库事务/审计；合成可信 principal 不代表真实 OAuth 端到端认证。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class PlatformLifecycleServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTransactionManager manager;
  private UserManagementMapper users;
  private PlatformLifecycleMapper lifecycle;
  private AccountManagementMapper accounts;
  private SecurityAuditRepository audit;
  private PlatformLifecycleService service;
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID pending = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();
  private final String prefix = "lifecycle-" + UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    var binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("app_env").asString()).isEqualTo("local");
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + binding.get("host").asString()
            + ":"
            + binding.get("port").asInt()
            + "/"
            + binding.get("dbname").asString());
    config.setUsername(binding.get("username").asString());
    config.setPassword(Files.readString(Path.of(binding.get("password_file").asString())).strip());
    config.setMaximumPoolSize(4);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    manager = new JdbcTransactionManager(pool);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    configuration.addMapper(UserManagementMapper.class);
    configuration.addMapper(AccountManagementMapper.class);
    configuration.addMapper(PlatformLifecycleMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var template = new SqlSessionTemplate(factory.getObject());
    users = template.getMapper(UserManagementMapper.class);
    accounts = template.getMapper(AccountManagementMapper.class);
    lifecycle = template.getMapper(PlatformLifecycleMapper.class);
    audit = new SecurityAuditRepository(jdbc);
    service = service(accounts, audit);
    for (UUID id : List.of(first, second, pending)) {
      jdbc.update(
          "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES (?,?,'原名称','{bcrypt}fixture',?)",
          id,
          prefix + "-" + id,
          id.equals(pending));
    }
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES (?,?,'原租户')", tenant, prefix);
    assertThat(lifecycle.effectiveAdministratorCount()).isEqualTo(2);
  }

  private PlatformLifecycleService service(
      AccountManagementMapper mapper, SecurityAuditRepository repository) {
    return new PlatformLifecycleService(
        manager,
        users,
        lifecycle,
        mapper,
        new PlatformManagementAuthorization(mapper),
        new ManagementAudits(repository, manager));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (pool != null) {
      try {
        jdbc.update("DELETE FROM sys_platform_user WHERE id IN (?,?,?)", first, second, pending);
      } finally {
        pool.close();
      }
    }
    // 随机测试租户被不可删审计引用，保留作为本轮证据，不改其他项目数据。
  }

  private <T> T platform(UUID actor, String request, String operation, Supplier<T> action) {
    return identity("platform", null, actor, request, operation, action);
  }

  private <T> T identity(
      String type,
      UUID tenantId,
      UUID actor,
      String request,
      String operation,
      Supplier<T> action) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_" + operation));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            tenantId == null
                ? Map.of("principal_type", type)
                : Map.of("principal_type", type, "tenant_id", tenantId.toString()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(new CallContext(request, tenantId, actor.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private PlatformLifecycleMapper.Administrator admin(
      UUID actor, UUID target, long version, String name, Status status) {
    return platform(
        actor,
        prefix,
        "platform:admin:manage",
        () -> service.updateAdministrator(target, version, name, status));
  }

  private PlatformLifecycleMapper.Tenant tenant(
      long version, String name, Instant expires, Status status) {
    return platform(
        first,
        prefix,
        "platform:tenant:manage",
        () -> service.updateTenant(tenant, version, name, expires, status));
  }

  private static void failure(
      String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            PlatformException.class,
            error -> {
              assertThat(error.code()).isEqualTo(code);
              assertThat(error.status()).isEqualTo(status);
            });
  }

  @Test
  void administratorMetadataDoesNotRevokeButStatusChangesAdvanceBothVersions() {
    var renamed = admin(first, second, 0, "新名称", Status.ACTIVE);
    assertThat(renamed.version()).isEqualTo(1);
    assertThat(renamed.authVersion()).isZero();
    assertThat(admin(first, second, 1, "新名称", Status.ACTIVE).version()).isEqualTo(1);
    var disabled = admin(first, second, 1, "新名称", Status.DISABLED);
    assertThat(disabled.version()).isEqualTo(2);
    assertThat(disabled.authVersion()).isEqualTo(1);
    var restored = admin(first, second, 2, "新名称", Status.ACTIVE);
    assertThat(restored.version()).isEqualTo(3);
    assertThat(restored.authVersion()).isEqualTo(2);
    failure(
        "PLATFORM_USER_VERSION_CONFLICT",
        409,
        () -> admin(first, second, 0, "旧表单", Status.DISABLED));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_platform_user WHERE id=?", String.class, second))
        .isEqualTo("ACTIVE");
  }

  @Test
  void pendingPasswordAdministratorCannotReplaceLastEffectiveAdministrator() {
    admin(first, second, 0, "原名称", Status.DISABLED);
    failure("LAST_PLATFORM_ADMIN", 409, () -> admin(first, first, 0, "原名称", Status.DISABLED));
    admin(first, pending, 0, "原名称", Status.DISABLED);
    assertThat(lifecycle.effectiveAdministratorCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_platform_user WHERE id=?", String.class, first))
        .isEqualTo("ACTIVE");
  }

  @Test
  void concurrentSelfDisableHasExactlyOneWinner() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs =
          List.of(first, second).stream()
              .map(
                  actor ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            try {
                              admin(actor, actor, 0, "原名称", Status.DISABLED);
                              return "OK";
                            } catch (PlatformException rejected) {
                              return rejected.code();
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  jobs.getFirst().get(10, TimeUnit.SECONDS),
                  jobs.getLast().get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "LAST_PLATFORM_ADMIN");
    }
    assertThat(lifecycle.effectiveAdministratorCount()).isEqualTo(1);
  }

  @Test
  void tenantRealmAndMissingAppPermissionAreDeniedAndAuditedWithValidatedActor() {
    failure(
        "INVALID_CREDENTIALS",
        401,
        () ->
            identity(
                "user",
                tenant,
                first,
                "tenant-denied",
                "platform:admin:manage",
                () -> service.updateAdministrator(second, 0, "改名", Status.ACTIVE)));
    failure(
        "FORBIDDEN",
        403,
        () ->
            platform(
                first,
                "scope-denied",
                "platform:tenant:manage",
                () -> service.updateAdministrator(second, 0, "改名", Status.ACTIVE)));
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_type FROM sys_auth_audit WHERE request_id='tenant-denied' AND actor_id=?",
                String.class,
                first))
        .isEqualTo("TENANT");
    assertThat(
            jdbc.queryForObject(
                "SELECT details->>'error_code' FROM sys_auth_audit WHERE request_id='scope-denied' AND actor_id=?",
                String.class,
                first))
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void missingPrincipalIsAnonymousRatherThanInventingPlatformActor() {
    try (var ignored =
        CallContexts.bind(
            new CallContext("anonymous-denied", null, first.toString(), null, null, null))) {
      failure(
          "INVALID_CREDENTIALS",
          401,
          () -> service.updateAdministrator(second, 0, "改名", Status.ACTIVE));
    }
    var row =
        jdbc.queryForMap(
            "SELECT actor_type,actor_id,tenant_id FROM sys_auth_audit WHERE request_id='anonymous-denied' AND target_id=?",
            second.toString());
    assertThat(row.get("actor_type")).isEqualTo("ANONYMOUS");
    assertThat(row.get("actor_id")).isNull();
    assertThat(row.get("tenant_id")).isNull();
  }

  @Test
  void tenantMetadataAndMicrosecondExpiryAreStableWithoutUnrelatedRevocation() {
    Instant expiry = Instant.parse("2027-01-01T00:00:00.123456789Z");
    var renamed = tenant(0, "新租户", expiry, Status.ACTIVE);
    assertThat(renamed.expiresAt()).isEqualTo(expiry.truncatedTo(ChronoUnit.MICROS));
    assertThat(renamed.version()).isEqualTo(1);
    assertThat(renamed.authVersion()).isZero();
    assertThat(tenant(1, "新租户", expiry, Status.ACTIVE).version()).isEqualTo(1);
    assertThat(tenant(1, "新租户", expiry, Status.DISABLED).authVersion()).isEqualTo(1);
    assertThat(tenant(2, "新租户", null, Status.ACTIVE).authVersion()).isEqualTo(2);
    failure("TENANT_VERSION_CONFLICT", 409, () -> tenant(0, "旧表单", null, Status.DISABLED));
    assertThat(
            jdbc.queryForObject("SELECT status FROM sys_tenant WHERE id=?", String.class, tenant))
        .isEqualTo("ACTIVE");
  }

  @Test
  void successfulAuditFailureRollsBackStateThenRecordsIndependentFailureWithoutMessage() {
    pool.getHikariConfigMXBean().setMinimumIdle(1);
    pool.getHikariConfigMXBean().setMaximumPoolSize(1);
    pool.getHikariPoolMXBean().softEvictConnections();
    var failing = spy(accounts);
    doThrow(new IllegalStateException("fixture-secret-must-not-be-audited"))
        .when(failing)
        .audit(any(), anyString(), any(), anyString(), anyString(), anyString(), anyString());
    service = service(failing, audit);
    assertThatThrownBy(() -> admin(first, second, 0, "新名称", Status.DISABLED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_platform_user WHERE id=?", String.class, second))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM sys_platform_user WHERE id=?", Long.class, second))
        .isZero();
    var event =
        jdbc.queryForMap(
            "SELECT result,details::text AS details FROM sys_auth_audit WHERE request_id=? AND target_id=?",
            prefix,
            second.toString());
    assertThat(event.get("result")).isEqualTo("FAILURE");
    assertThat((String) event.get("details"))
        .contains("INTERNAL_ERROR")
        .doesNotContain("fixture-secret", "password", "hash");
  }

  @Test
  void failureAuditOutageIsExplicitAndCannotTurnFailureIntoSuccess() {
    var unavailable = spy(audit);
    doThrow(new IllegalStateException("fixture-audit-offline"))
        .when(unavailable)
        .failure(any(), anyString(), any(), anyString(), any(), anyString(), anyString());
    service = service(accounts, unavailable);
    failure(
        "SECURITY_AUDIT_UNAVAILABLE", 503, () -> admin(first, second, 999, "改名", Status.DISABLED));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM sys_platform_user WHERE id=?", Long.class, second))
        .isZero();
  }

  @Test
  void concurrentTenantEditsCannotSilentlyOverwriteEachOther() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs =
          List.of("甲租户", "乙租户").stream()
              .map(
                  name ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            try {
                              tenant(0, name, null, Status.ACTIVE);
                              return "OK";
                            } catch (PlatformException rejected) {
                              return rejected.code();
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  jobs.getFirst().get(10, TimeUnit.SECONDS),
                  jobs.getLast().get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "TENANT_VERSION_CONFLICT");
    }
    assertThat(jdbc.queryForObject("SELECT version FROM sys_tenant WHERE id=?", Long.class, tenant))
        .isEqualTo(1);
  }

  @Test
  void malformedPrincipalMetadataStaysAnonymousAndDoesNotLeakTokenAttributes() {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_platform:admin:manage"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            first.toString(),
            Map.of("principal_type", 123, "access_token", "fixture-token-not-for-audit"),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext(
                "malformed-actor",
                null,
                first.toString(),
                "fixture-token-not-for-audit",
                null,
                null))) {
      failure(
          "INVALID_CREDENTIALS",
          401,
          () -> service.updateAdministrator(second, 0, "改名", Status.ACTIVE));
    }
    var event =
        jdbc.queryForMap(
            "SELECT actor_type,details::text AS details FROM sys_auth_audit WHERE request_id='malformed-actor' AND target_id=?",
            second.toString());
    assertThat(event.get("actor_type")).isEqualTo("ANONYMOUS");
    assertThat((String) event.get("details"))
        .contains("INVALID_CREDENTIALS")
        .doesNotContain("fixture-token", "access_token");
  }

  @Test
  void disabledOrPasswordPendingPlatformActorCannotUseOldApplicationAuthority() {
    admin(first, second, 0, "原名称", Status.DISABLED);
    failure("INVALID_CREDENTIALS", 401, () -> admin(second, second, 1, "原名称", Status.ACTIVE));
    failure("INVALID_CREDENTIALS", 401, () -> admin(pending, first, 0, "改名", Status.ACTIVE));
    failure(
        "INVALID_CREDENTIALS",
        401,
        () ->
            platform(
                second,
                "disabled-tenant-edit",
                "platform:tenant:manage",
                () -> service.updateTenant(tenant, 0, "改名", null, Status.ACTIVE)));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_platform_user WHERE id=?", String.class, second))
        .isEqualTo("DISABLED");
    assertThat(jdbc.queryForObject("SELECT version FROM sys_tenant WHERE id=?", Long.class, tenant))
        .isZero();
  }

  @Test
  void outerTransactionIsRejectedBeforeBusinessOrIndependentAudit() {
    assertThatThrownBy(
            () ->
                platform(
                    first,
                    prefix,
                    "platform:admin:manage",
                    () ->
                        new TransactionTemplate(manager)
                            .execute(
                                status ->
                                    service.updateAdministrator(second, 0, "改名", Status.ACTIVE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("管理入口必须在外层事务之外执行");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE request_id=?", Integer.class, prefix))
        .isZero();
  }
}
