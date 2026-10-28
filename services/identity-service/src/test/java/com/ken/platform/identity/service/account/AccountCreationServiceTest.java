package com.ken.platform.identity.service.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.json.JsonMapper;

/** 真实事务验证；合成 OAuth principal 只表示本测试的受信任适配边界，不算真实登录。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class AccountCreationServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTransactionManager manager;
  private TenantTransactions transactions;
  private AccountManagementMapper accounts;
  private UserManagementMapper users;
  private SecurityPolicies policies;
  private PasswordCredentials passwords;
  private AccountCreationService service;
  private PermissionCatalogMapper catalogMapper;
  private BuiltinPermissionCatalog catalog;
  private Clock authenticationClock = Clock.systemUTC();
  private UUID tenantA;
  private UUID tenantB;
  private final UUID platformActor = UUID.randomUUID();
  private final UUID adminA = UUID.randomUUID();
  private final UUID adminB = UUID.randomUUID();
  private final UUID roleA = UUID.randomUUID();
  private final UUID roleB = UUID.randomUUID();
  private final String prefix = "creation-" + UUID.randomUUID();
  private final List<PasswordCredentials.OneTimeSecret> secrets = new ArrayList<>();

  private ManagementAudits audits() {
    return new ManagementAudits(
        new SecurityAuditRepository(jdbc), new JdbcTransactionManager(pool));
  }

  @BeforeEach
  void setup() throws Exception {
    var binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("app_env").asString()).isEqualTo("local");
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    tenantA = UUID.fromString(binding.get("tenant_a").asString());
    tenantB = UUID.fromString(binding.get("tenant_b").asString());
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
    transactions = new TenantTransactions(jdbc, manager);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    configuration.addMapper(AccountManagementMapper.class);
    configuration.addMapper(UserManagementMapper.class);
    configuration.addMapper(SecurityPolicyMapper.class);
    configuration.addMapper(PermissionCatalogMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var template = new SqlSessionTemplate(factory.getObject());
    catalogMapper = template.getMapper(PermissionCatalogMapper.class);
    catalog = new BuiltinPermissionCatalog(catalogMapper, manager);
    catalog.install();
    accounts = template.getMapper(AccountManagementMapper.class);
    users = template.getMapper(UserManagementMapper.class);
    var policyMapper = template.getMapper(SecurityPolicyMapper.class);
    policies = new SecurityPolicies(policyMapper);
    passwords = new PasswordCredentials(policyMapper);
    service = service(accounts, passwords);
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES (?,?,'平台管理员','{bcrypt}fixture',false)",
        platformActor,
        prefix);
    seedAdmin(tenantA, adminA, roleA);
    seedAdmin(tenantB, adminB, roleB);
  }

  private AccountCreationService service(
      AccountManagementMapper mapper, PasswordCredentials encoder) {
    return service(mapper, encoder, Clock.systemUTC());
  }

  private AccountCreationService service(
      AccountManagementMapper mapper, PasswordCredentials encoder, Clock clock) {
    authenticationClock = clock;
    return new AccountCreationService(
        manager,
        transactions,
        mapper,
        users,
        new PlatformManagementAuthorization(mapper),
        new TenantManagementAuthorization(users),
        policies,
        encoder,
        clock,
        audits(),
        new com.ken.platform.identity.service.security.RecentAuthentication(policies, clock),
        new com.ken.platform.identity.service.oauth.ClientManagementService(
            manager,
            new com.ken.platform.identity.dal.mapper.oauth.ClientStore(jdbc),
            new com.ken.platform.identity.service.oauth.ClientPolicyRules("local"),
            users,
            mapper,
            new PlatformManagementAuthorization(mapper),
            audits(),
            encoder,
            clock,
            new com.ken.platform.identity.service.security.RecentAuthentication(policies, clock)));
  }

  private void seedAdmin(UUID tenant, UUID actor, UUID role) {
    data(
        tenant,
        () -> {
          jdbc.update(
              "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES (?,?,?,'管理员','{bcrypt}fixture','ACTIVE',false)",
              actor,
              tenant,
              prefix + "-admin");
          accounts.createProtectedRole(role, tenant);
          accounts.grantCorePermissions(
              role, tenant, BuiltinPermissionCatalog.PROTECTED_TENANT_PERMISSIONS);
          accounts.initialMembership(tenant, actor, role);
          return null;
        });
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    secrets.forEach(PasswordCredentials.OneTimeSecret::close);
    if (pool == null) {
      return;
    }
    try {
      var createdTenants =
          jdbc.queryForList(
              "SELECT id FROM sys_tenant WHERE code LIKE ?", UUID.class, prefix + "%");
      var all = new ArrayList<>(createdTenants);
      all.addAll(List.of(tenantA, tenantB));
      for (UUID tenant : all) {
        data(
            tenant,
            () -> {
              boolean created = createdTenants.contains(tenant);
              String restriction = created ? "" : " AND normalized_username LIKE ?";
              var ids =
                  created
                      ? jdbc.queryForList(
                          "SELECT id FROM sys_user WHERE tenant_id=?", UUID.class, tenant)
                      : jdbc.queryForList(
                          "SELECT id FROM sys_user WHERE tenant_id=?" + restriction,
                          UUID.class,
                          tenant,
                          prefix + "%");
              for (UUID user : ids) {
                jdbc.update(
                    "DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?", tenant, user);
              }
              for (UUID role :
                  created
                      ? jdbc.queryForList(
                          "SELECT id FROM sys_role WHERE tenant_id=?", UUID.class, tenant)
                      : List.of(tenant.equals(tenantA) ? roleA : roleB)) {
                jdbc.update(
                    "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                    tenant,
                    role);
                jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, role);
              }
              for (UUID user : ids) {
                jdbc.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", tenant, user);
              }
              return null;
            });
      }
      jdbc.update("DELETE FROM sys_platform_user WHERE normalized_username LIKE ?", prefix + "%");
      jdbc.update(
          "UPDATE sys_tenant SET status='ACTIVE',expires_at=NULL WHERE id IN (?,?)",
          tenantA,
          tenantB);
      // 审计与幂等记录不可删，保留随机租户目录作为本轮证据；所有租户业务数据已精确清理。
    } finally {
      pool.close();
    }
  }

  private <T> T data(UUID tenant, Supplier<T> action) {
    try (var ignored =
        CallContexts.bind(new CallContext("creation-data", tenant, "fixture", null, null, null))) {
      return transactions.execute(action);
    }
  }

  private <T> T authorized(
      String type, UUID tenant, UUID actor, String request, String operation, Supplier<T> action) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_" + operation));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            tenant == null
                ? Map.of(
                    "principal_type",
                    type,
                    com.ken.platform.identity.service.oauth.UserTokenService.AUTHENTICATED_AT,
                    authenticationClock.instant())
                : Map.of(
                    "principal_type",
                    type,
                    "tenant_id",
                    tenant.toString(),
                    com.ken.platform.identity.service.oauth.UserTokenService.AUTHENTICATED_AT,
                    authenticationClock.instant()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(new CallContext(request, tenant, actor.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private AccountCreationService.TenantCommand command(String suffix) {
    return new AccountCreationService.TenantCommand(
        prefix + suffix, "测试租户", null, " ADMIN ", "初始管理员");
  }

  private AccountCreationService.CreatedTenant tenant(String request, String suffix) {
    return authorized(
        "platform",
        null,
        platformActor,
        request,
        "platform:tenant:manage",
        () -> service.createTenant(command(suffix)));
  }

  private AccountCreationService.CreatedAccount user(
      UUID tenant, UUID actor, String request, String username) {
    return authorized(
        "user",
        tenant,
        actor,
        request,
        "identity:user:manage",
        () -> service.createUser(username, "测试用户"));
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
  void tenantAndPendingAdministratorAreAtomicWithoutBusinessGrantsOrActivationEvent() {
    var created = tenant("create-tenant", "-one");
    secrets.add(created.credential());
    String clear = created.credential().revealOnce();
    data(
        created.tenantId(),
        () -> {
          var row =
              jdbc.queryForMap(
                  "SELECT normalized_username,password_hash,status,must_change_password FROM sys_user WHERE id=?",
                  created.adminId());
          assertThat(row.get("normalized_username")).isEqualTo("admin");
          assertThat(row.get("status")).isEqualTo("PENDING_ACTIVATION");
          assertThat(row.get("must_change_password")).isEqualTo(true);
          assertThat(passwords.matches(clear, (String) row.get("password_hash"))).isTrue();
          assertThat(
                  jdbc.queryForList(
                      "SELECT permission_code FROM sys_role_permission ORDER BY permission_code",
                      String.class))
              .containsExactly(
                  "identity:audit:read",
                  "identity:permission:read",
                  "identity:role:assign",
                  "identity:role:manage",
                  "identity:role:read",
                  "identity:session:manage",
                  "identity:session:read",
                  "identity:user:manage",
                  "identity:user:read");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT protected FROM sys_role WHERE code='tenant-admin'", Boolean.class))
              .isTrue();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_user_role WHERE user_id=?",
                      Integer.class,
                      created.adminId()))
              .isEqualTo(1);
          return null;
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT details::text FROM sys_auth_audit WHERE target_id=?",
                String.class,
                created.tenantId().toString()))
        .doesNotContain(clear, "bcrypt");
    assertThat(
            jdbc.queryForObject(
                "SELECT result_data::text FROM identity_technical.management_request WHERE actor_id=? AND request_id='create-tenant'",
                String.class,
                platformActor))
        .doesNotContain(clear, "bcrypt");
    assertThat(jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class))
        .isNullOrEmpty();
  }

  @Test
  void sameUsernameIsIndependentAcrossTenantsAndPlatformAndGetsNoAutomaticRole() {
    String username = prefix + "-same";
    var first =
        user(
            tenantA,
            adminA,
            "same-request",
            " " + username.toUpperCase(java.util.Locale.ROOT) + " ");
    var second = user(tenantB, adminB, "same-request", username);
    var platform =
        authorized(
            "platform",
            null,
            platformActor,
            "same-request",
            "platform:admin:manage",
            () -> service.createPlatformAdministrator(username, "平台账号"));
    secrets.addAll(List.of(first.credential(), second.credential(), platform.credential()));
    assertThat(first.id()).isNotEqualTo(second.id()).isNotEqualTo(platform.id());
    for (UUID tenant : List.of(tenantA, tenantB)) {
      data(
          tenant,
          () -> {
            UUID id = tenant.equals(tenantA) ? first.id() : second.id();
            assertThat(
                    jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", String.class, id))
                .isEqualTo("PENDING_ACTIVATION");
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_user_role WHERE user_id=?", Integer.class, id))
                .isZero();
            return null;
          });
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT must_change_password FROM sys_platform_user WHERE id=?",
                Boolean.class,
                platform.id()))
        .isTrue();
  }

  @Test
  void duplicateRequestNeverReplaysCredentialAndChangedPayloadConflicts() {
    var first = user(tenantA, adminA, "idempotent", prefix + "-one");
    secrets.add(first.credential());
    var again = user(tenantA, adminA, "idempotent", prefix + "-one");
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(again.replayed()).isTrue();
    assertThat(again.credential()).isNull();
    failure("REQUEST_ID_CONFLICT", 409, () -> user(tenantA, adminA, "idempotent", prefix + "-two"));
    failure("USERNAME_CONFLICT", 409, () -> user(tenantA, adminA, "new-request", prefix + "-one"));
    var created = tenant("tenant-idempotent", "-repeat");
    secrets.add(created.credential());
    assertThat(tenant("tenant-idempotent", "-repeat").credential()).isNull();
    failure("TENANT_CODE_CONFLICT", 409, () -> tenant("other-tenant-request", "-repeat"));
  }

  @Test
  void platformAndTenantPrincipalBoundariesAreRecheckedBeforeReplay() {
    failure(
        "INVALID_CREDENTIALS",
        401,
        () ->
            authorized(
                "user",
                tenantA,
                adminA,
                "wrong",
                "platform:tenant:manage",
                () -> service.createTenant(command("-forbidden"))));
    failure(
        "FORBIDDEN",
        403,
        () ->
            authorized(
                "platform",
                null,
                platformActor,
                "wrong",
                "platform:admin:manage",
                () -> service.createTenant(command("-forbidden"))));
    failure(
        "INVALID_CREDENTIALS", 401, () -> user(tenantB, adminA, "wrong-tenant", prefix + "-wrong"));
    var created = tenant("recheck", "-recheck");
    secrets.add(created.credential());
    jdbc.update("UPDATE sys_platform_user SET status='DISABLED' WHERE id=?", platformActor);
    failure("INVALID_CREDENTIALS", 401, () -> tenant("recheck", "-recheck"));
    data(
        tenantA,
        () -> {
          jdbc.update("UPDATE sys_user SET must_change_password=true WHERE id=?", adminA);
          return null;
        });
    failure(
        "INVALID_CREDENTIALS", 401, () -> user(tenantA, adminA, "pending-admin", prefix + "-no"));
  }

  @Test
  void disabledOrExpiredTenantCannotCreateUsers() {
    jdbc.update("UPDATE sys_tenant SET status='DISABLED' WHERE id=?", tenantA);
    failure(
        "TENANT_UNAVAILABLE", 403, () -> user(tenantA, adminA, "disabled", prefix + "-blocked"));
    jdbc.update(
        "UPDATE sys_tenant SET status='ACTIVE',expires_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        tenantA);
    failure("TENANT_UNAVAILABLE", 403, () -> user(tenantA, adminA, "expired", prefix + "-blocked"));
    failure(
        "TENANT_FIELDS_INVALID",
        400,
        () ->
            authorized(
                "platform",
                null,
                platformActor,
                "bad-expiry",
                "platform:tenant:manage",
                () ->
                    service.createTenant(
                        new AccountCreationService.TenantCommand(
                            prefix + "-expired", "租户", Instant.EPOCH, "admin", "管理员"))));
  }

  @Test
  void auditFailureRollsBackTenantRolesUsersAndDedupAndDestroysSecret() {
    var failing = spy(accounts);
    doThrow(new IllegalStateException("fixture-audit-failure"))
        .when(failing)
        .audit(any(), anyString(), any(), anyString(), anyString(), anyString(), anyString());
    var observed = spy(passwords);
    var material = new AtomicReference<PasswordCredentials.Prepared>();
    doAnswer(
            invocation -> {
              var result = (PasswordCredentials.Prepared) invocation.callRealMethod();
              material.set(result);
              return result;
            })
        .when(observed)
        .temporary(any());
    service = service(failing, observed);
    assertThatThrownBy(() -> tenant("failed-create", "-failed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fixture-audit-failure");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_tenant WHERE code=?", Integer.class, prefix + "-failed"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity_technical.management_request WHERE actor_id=? AND request_id='failed-create'",
                Integer.class,
                platformActor))
        .isZero();
    assertThat(material.get()).isNotNull();
    assertThatThrownBy(material.get().secret()::revealOnce)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentSameRequestCreatesOneAccountAndDeliversOnlyOneCredential() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          List.of(1, 2).stream()
              .map(
                  index ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            return user(
                                tenantA, adminA, "concurrent-create", prefix + "-concurrent");
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var first = pending.getFirst().get(10, TimeUnit.SECONDS);
      var second = pending.getLast().get(10, TimeUnit.SECONDS);
      assertThat(first.id()).isEqualTo(second.id());
      assertThat(List.of(first.replayed(), second.replayed()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(
              java.util.stream.Stream.of(first.credential(), second.credential())
                  .filter(java.util.Objects::nonNull))
          .hasSize(1);
      secrets.add(first.credential() == null ? second.credential() : first.credential());
    }
  }

  @Test
  void replayAfterRequestedExpiryReturnsOriginalMetadataWithoutNewCredential() {
    Instant start = Instant.parse("2026-10-26T00:00:00Z");
    var request =
        new AccountCreationService.TenantCommand(
            prefix + "-expiry-replay", "租户", start.plusSeconds(600), "admin", "管理员");
    service = service(accounts, passwords, Clock.fixed(start, java.time.ZoneOffset.UTC));
    var first =
        authorized(
            "platform",
            null,
            platformActor,
            "expiry-replay",
            "platform:tenant:manage",
            () -> service.createTenant(request));
    secrets.add(first.credential());
    service =
        service(accounts, passwords, Clock.fixed(start.plusSeconds(601), java.time.ZoneOffset.UTC));
    var again =
        authorized(
            "platform",
            null,
            platformActor,
            "expiry-replay",
            "platform:tenant:manage",
            () -> service.createTenant(request));
    assertThat(again.tenantId()).isEqualTo(first.tenantId());
    assertThat(again.replayed()).isTrue();
    assertThat(again.credential()).isNull();
    failure(
        "TENANT_FIELDS_INVALID",
        400,
        () ->
            authorized(
                "platform",
                null,
                platformActor,
                "expired-new-request",
                "platform:tenant:manage",
                () -> service.createTenant(request)));
  }

  @Test
  void outerTransactionCannotExposeCredentialBeforeItsOwnCommit() {
    assertThatThrownBy(
            () ->
                authorized(
                    "user",
                    tenantA,
                    adminA,
                    "outer-transaction",
                    "identity:user:manage",
                    () ->
                        transactions.execute(() -> service.createUser(prefix + "-nested", "嵌套创建"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("账号创建必须通过独立事务入口执行");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity_technical.management_request WHERE actor_id=? AND request_id='outer-transaction'",
                Integer.class,
                adminA))
        .isZero();
    assertThat(
            data(
                tenantA,
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_user WHERE normalized_username=?",
                        Integer.class,
                        prefix + "-nested")))
        .isZero();
  }

  @Test
  void missingControlledIdentityCatalogStopsBeforeCreatingTenant() {
    jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE code='identity:role:assign'");
    try {
      failure("IDENTITY_CATALOG_NOT_READY", 503, () -> tenant("not-ready", "-not-ready"));
    } finally {
      jdbc.update("UPDATE sys_permission SET status='ACTIVE' WHERE code='identity:role:assign'");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_tenant WHERE code=?",
                Integer.class,
                prefix + "-not-ready"))
        .isZero();
  }

  @Test
  void allCreationFailuresAreAuditedWithoutReplayingCredentialsOrRequestInput() {
    String code =
        jdbc.queryForObject("SELECT code FROM sys_tenant WHERE id=?", String.class, tenantA);
    var duplicate =
        new AccountCreationService.TenantCommand(
            code, "fixture-private-input", null, "admin", "管理员");
    failure(
        "TENANT_CODE_CONFLICT",
        409,
        () ->
            authorized(
                "platform",
                null,
                platformActor,
                "failed-tenant-create",
                "platform:tenant:manage",
                () -> service.createTenant(duplicate)));
    failure(
        "PLATFORM_USERNAME_CONFLICT",
        409,
        () ->
            authorized(
                "platform",
                null,
                platformActor,
                "failed-platform-create",
                "platform:admin:manage",
                () -> service.createPlatformAdministrator(prefix, "fixture-private-input")));
    failure(
        "USERNAME_CONFLICT",
        409,
        () ->
            authorized(
                "user",
                tenantA,
                adminA,
                "failed-user-create",
                "identity:user:manage",
                () -> service.createUser(prefix + "-admin", "fixture-private-input")));
    var rows =
        jdbc.queryForList(
            "SELECT action,result,details::text AS details FROM sys_auth_audit WHERE actor_id IN (?,?) AND request_id IN ('failed-tenant-create','failed-platform-create','failed-user-create') ORDER BY action",
            platformActor,
            adminA);
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().map(row -> row.get("action")))
        .containsExactly(
            "identity.user.created", "platform.admin.created", "platform.tenant.created");
    rows.forEach(
        row -> {
          assertThat(row.get("result")).isEqualTo("FAILURE");
          assertThat((String) row.get("details"))
              .doesNotContain("fixture-private", "bcrypt", "password");
        });
  }

  @Test
  void builtinCatalogHasSeparatePlatformOperationsAndRepeatDoesNotGrantExistingRoles() {
    long version =
        jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class);
    long beforeAudit = jdbc.queryForObject("SELECT count(*) FROM sys_auth_audit", Long.class);
    var beforeRoles =
        data(
            tenantA,
            () ->
                jdbc.queryForList(
                    "SELECT role_id,permission_code,data_scope FROM sys_role_permission ORDER BY role_id,permission_code"));
    var catalogBefore = catalogMapper.all();
    var result = catalog.install();
    assertThat(result.catalogVersion()).isEqualTo(version);
    assertThat(result.changedCodes()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_auth_audit", Long.class))
        .isEqualTo(beforeAudit);
    assertThat(
            data(
                tenantA,
                () ->
                    jdbc.queryForList(
                        "SELECT role_id,permission_code,data_scope FROM sys_role_permission ORDER BY role_id,permission_code")))
        .isEqualTo(beforeRoles);
    var catalogAfter = catalogMapper.all();
    assertThat(catalogAfter).isEqualTo(catalogBefore);
    var builtinCodes =
        BuiltinPermissionCatalog.entries().stream()
            .map(row -> row.code())
            .collect(java.util.stream.Collectors.toSet());
    assertThat(catalogAfter.stream().filter(row -> builtinCodes.contains(row.code())))
        .hasSize(25)
        .containsAll(BuiltinPermissionCatalog.entries());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE principal_type='PLATFORM' AND cardinality(supported_data_scopes)<>0",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service='identity-service' AND principal_type='TENANT' AND supported_data_scopes<>ARRAY['TENANT']::text[]",
                Integer.class))
        .isZero();
  }

  @Test
  void builtinCatalogRejectsConflictingMeaningAndCannotRestoreDisabledOperation() {
    long version =
        jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class);
    try {
      jdbc.update(
          "UPDATE sys_permission SET oauth_scope='different.meaning' WHERE code='identity:audit:read'");
      failure("BUILTIN_PERMISSION_CONFLICT", 409, catalog::install);
      jdbc.update(
          "UPDATE sys_permission SET oauth_scope='identity.audit',status='DISABLED' WHERE code='identity:audit:read'");
      failure("BUILTIN_PERMISSION_CONFLICT", 409, catalog::install);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM sys_permission WHERE code='identity:audit:read'",
                  String.class))
          .isEqualTo("DISABLED");
      assertThat(
              jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class))
          .isEqualTo(version);
    } finally {
      jdbc.update(
          "UPDATE sys_permission SET oauth_scope='identity.audit',status='ACTIVE' WHERE code='identity:audit:read'");
    }
  }

  @Test
  void builtinCatalogAuditFailureRollsBackPresentationAndCatalogVersion() {
    var failing = spy(catalogMapper);
    doThrow(new IllegalStateException("DO_NOT_EXPOSE_CATALOG_AUDIT"))
        .when(failing)
        .audit(anyString(), anyString(), anyString());
    long version =
        jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class);
    try {
      jdbc.update("UPDATE sys_permission SET name='待更新名称' WHERE code='identity:audit:read'");
      assertThatThrownBy(() -> new BuiltinPermissionCatalog(failing, manager).install())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("DO_NOT_EXPOSE_CATALOG_AUDIT");
      assertThat(
              jdbc.queryForObject(
                  "SELECT name FROM sys_permission WHERE code='identity:audit:read'", String.class))
          .isEqualTo("待更新名称");
      assertThat(
              jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class))
          .isEqualTo(version);
    } finally {
      catalog.install();
    }
  }

  @Test
  void concurrentBuiltinInstallUpdatesPresentationOnceAndKeepsBusinessDirectory() throws Exception {
    long version =
        jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class);
    var business =
        catalogMapper.all().stream()
            .filter(row -> !row.ownerService().equals("identity-service"))
            .toList();
    jdbc.update("UPDATE sys_permission SET name='并发维护前' WHERE code='identity:audit:read'");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var work =
          List.of(1, 2).stream()
              .map(
                  i ->
                      executor.submit(
                          () -> {
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            return catalog.install();
                          }))
              .toList();
      start.countDown();
      var first = work.getFirst().get(10, TimeUnit.SECONDS);
      var second = work.getLast().get(10, TimeUnit.SECONDS);
      assertThat(List.of(first.changedCodes().size(), second.changedCodes().size()))
          .containsExactlyInAnyOrder(0, 1);
      assertThat(
              jdbc.queryForObject("SELECT version FROM sys_permission_catalog_state", Long.class))
          .isEqualTo(version + 1);
      assertThat(
              catalogMapper.all().stream()
                  .filter(row -> !row.ownerService().equals("identity-service"))
                  .toList())
          .isEqualTo(business);
    } finally {
      catalog.install();
    }
  }

  @Test
  void missingReadPermissionStopsNewTenantBeforeCreatingAccountAndRole() {
    try {
      jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE code='identity:user:read'");
      failure("IDENTITY_CATALOG_NOT_READY", 503, () -> tenant("missing-read", "-missing-read"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_tenant WHERE code=?",
                  Integer.class,
                  prefix + "-missing-read"))
          .isZero();
    } finally {
      jdbc.update("UPDATE sys_permission SET status='ACTIVE' WHERE code='identity:user:read'");
    }
  }
}
