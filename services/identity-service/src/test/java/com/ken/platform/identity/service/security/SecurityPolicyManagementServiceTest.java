package com.ken.platform.identity.service.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyStore;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 真实隔离 PostgreSQL；合成管理主体仅验证用例边界，HTTP 动态生效另有原生场景。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class SecurityPolicyManagementServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTransactionManager manager;
  private SecurityPolicyMapper policies;
  private SecurityPolicyStore store;
  private AccountManagementMapper accounts;
  private SecurityAuditRepository audit;
  private SecurityPolicyManagementService service;
  private Map<String, Integer> original;
  private final UUID platform = UUID.randomUUID(),
      tenant = UUID.randomUUID(),
      user = UUID.randomUUID(),
      role = UUID.randomUUID();

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
    config.setMaximumPoolSize(5);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    manager = new JdbcTransactionManager(pool);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    for (Class<?> type :
        List.of(
            SecurityPolicyMapper.class, AccountManagementMapper.class, UserManagementMapper.class))
      configuration.addMapper(type);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var sql = new SqlSessionTemplate(factory.getObject());
    policies = sql.getMapper(SecurityPolicyMapper.class);
    store = new SecurityPolicyStore(jdbc);
    accounts = spy(sql.getMapper(AccountManagementMapper.class));
    audit = spy(new SecurityAuditRepository(jdbc));
    var users = sql.getMapper(UserManagementMapper.class);
    service =
        new SecurityPolicyManagementService(
            policies,
            store,
            users,
            accounts,
            new PlatformManagementAuthorization(accounts),
            new ManagementAudits(audit, manager),
            manager);
    original = values();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'策略平台管理员','fixture',false)",
        platform,
        "policy-" + platform);
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'策略租户')", tenant, "policy-" + tenant);
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'策略管理员','fixture','ACTIVE',false)",
              user,
              tenant,
              "policy-" + user);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,'tenant-admin','管理员',true)",
              role,
              tenant);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              role);
          return null;
        });
  }

  private Map<String, Integer> values() {
    var rows =
        jdbc.queryForMap(
            "SELECT "
                + String.join(",", SecurityPolicyStore.FIELDS)
                + " FROM sys_security_policy WHERE owner_type='PLATFORM'");
    var result = new HashMap<String, Integer>();
    rows.forEach((key, value) -> result.put(key, (Integer) value));
    return result;
  }

  private <T> T data(Supplier<T> action) {
    return new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)", String.class, tenant.toString());
              return action.get();
            });
  }

  private <T> T as(boolean isPlatform, String operation, Supplier<T> action) {
    UUID actor = isPlatform ? platform : user;
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_" + operation));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            isPlatform
                ? Map.of("principal_type", "platform")
                : Map.of("principal_type", "user", "tenant_id", tenant.toString()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext(
                UUID.randomUUID().toString(),
                isPlatform ? null : tenant,
                actor.toString(),
                null,
                null,
                null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private <T> T platform(Supplier<T> action) {
    return as(true, "platform:security:manage", action);
  }

  private <T> T tenant(Supplier<T> action) {
    return as(false, "identity:security:manage", action);
  }

  private long base() {
    return policies.current(null).getFirst().version();
  }

  private void code(Throwable error, String code, int status) {
    assertThat(error).isInstanceOf(PlatformException.class);
    assertThat(((PlatformException) error).code()).isEqualTo(code);
    assertThat(((PlatformException) error).status()).isEqualTo(status);
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (pool == null) return;
    try {
      new TransactionTemplate(manager)
          .execute(
              status -> {
                var rows = policies.current(tenant);
                var own =
                    rows.stream()
                        .filter(row -> row.ownerType().equals("TENANT"))
                        .findFirst()
                        .orElse(null);
                if (own != null) {
                  Map<String, Integer> inherited = new HashMap<>();
                  SecurityPolicyStore.FIELDS.forEach(field -> inherited.put(field, null));
                  store.update(tenant, own.version(), inherited);
                }
                store.update(null, base(), original);
                return null;
              });
    } finally {
      pool.close();
    }
  }

  @Test
  void projectPolicyIsVersionedAndSharedByTenants() {
    long start = base();
    var changed =
        platform(
            () ->
                service.updatePlatform(
                    start, Map.of("password_min_chars", 17, "login_failure_threshold", 8)));
    assertThat(changed.version()).isEqualTo(start + 1);
    assertThat(changed.effective().passwordMinChars()).isEqualTo(17);
    assertThat(new SecurityPolicies(policies).current(tenant).passwordMinChars()).isEqualTo(17);
    assertThat(new SecurityPolicies(policies).current(tenant).tenantVersion()).isNull();
  }

  @Test
  void noOpDoesNotCreateOverrideOrAdvanceVersionButStaleVersionStillFails() {
    long version = base();
    assertThat(platform(() -> service.updatePlatform(version, Map.of())).version())
        .isEqualTo(version);
    assertThat(policies.current(tenant)).hasSize(1);
    code(
        catchThrowable(() -> platform(() -> service.updatePlatform(version + 1, Map.of()))),
        "SECURITY_POLICY_VERSION_CONFLICT",
        409);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unknown", "fixed", "decimal", "string", "low", "high", "cross", "platform-null"})
  void badPlatformInputNeverWrites(String kind) {
    long version = base();
    Map<String, Object> patch = new HashMap<>();
    switch (kind) {
      case "unknown" -> patch.put("custom_password_pattern", "DO_NOT_ECHO_POLICY");
      case "fixed" -> patch.put("password_blocklist_enabled", false);
      case "decimal" -> patch.put("password_min_chars", 17.5);
      case "string" -> patch.put("password_min_chars", "20");
      case "low" -> patch.put("password_min_chars", 14);
      case "high" -> patch.put("login_failure_threshold", 11);
      case "cross" -> patch.put("session_absolute_seconds", 300);
      default -> patch.put("password_min_chars", null);
    }
    var error = catchThrowable(() -> platform(() -> service.updatePlatform(version, patch)));
    code(error, "SECURITY_POLICY_INVALID", 400);
    assertThat(error.getMessage()).doesNotContain("DO_NOT_ECHO_POLICY");
    assertThat(base()).isEqualTo(version);
  }

  @Test
  void tenantCannotChangeProjectPolicy() {
    code(
        catchThrowable(
            () -> tenant(() -> service.updatePlatform(base(), Map.of("password_min_chars", 18)))),
        "INVALID_CREDENTIALS",
        401);
    assertThat(policies.current(tenant)).hasSize(1);
  }

  @Test
  void platformPreflightRejectsConflictingExistingOverrideAtomically() {
    long version = base();
    jdbc.update(
        "INSERT INTO sys_security_policy(owner_type,owner_tenant_id,password_min_chars) VALUES('TENANT',?,20)",
        tenant);
    code(
        catchThrowable(
            () ->
                platform(() -> service.updatePlatform(version, Map.of("password_min_chars", 17)))),
        "SECURITY_POLICY_UNIFICATION_REQUIRED",
        409);
    assertThat(base()).isEqualTo(version);
    code(
        catchThrowable(() -> new SecurityPolicies(policies).current(tenant)),
        "SECURITY_POLICY_UNIFICATION_REQUIRED",
        409);
    var changed = platform(() -> service.updatePlatform(version, Map.of("password_min_chars", 20)));
    assertThat(changed.effective().passwordMinChars()).isEqualTo(20);
    assertThat(new SecurityPolicies(policies).current(tenant).tenantVersion()).isNull();
  }

  @Test
  void wrongApplicationOrDisabledActorCannotSave() {
    long version = base();
    code(
        catchThrowable(
            () ->
                as(
                    true,
                    "unrelated:operation",
                    () -> service.updatePlatform(version, Map.of("password_min_chars", 18)))),
        "FORBIDDEN",
        403);
    jdbc.update("UPDATE sys_platform_user SET status='DISABLED' WHERE id=?", platform);
    code(
        catchThrowable(
            () ->
                platform(() -> service.updatePlatform(version, Map.of("password_min_chars", 18)))),
        "INVALID_CREDENTIALS",
        401);
  }

  @Test
  void twoConcurrentProjectSavesHaveOneWinnerAndOneConflict() throws Exception {
    long version = base();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var work =
          (java.util.concurrent.Callable<Throwable>)
              () -> {
                ready.countDown();
                assertThat(start.await(3, TimeUnit.SECONDS)).isTrue();
                return catchThrowable(
                    () ->
                        platform(
                            () ->
                                service.updatePlatform(version, Map.of("password_min_chars", 18))));
              };
      var a = workers.submit(work);
      var b = workers.submit(work);
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var errors = java.util.Arrays.asList(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS));
      assertThat(errors.stream().filter(value -> value == null).count()).isEqualTo(1);
      code(
          errors.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow(),
          "SECURITY_POLICY_VERSION_CONFLICT",
          409);
    }
  }

  @Test
  void successAuditFailureRollsBackPolicyAndIndependentFailureIsRetained() {
    long version = base();
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_POLICY_STORAGE"))
        .when(accounts)
        .audit(any(), any(), any(), any(), any(), any(), any());
    assertThatThrownBy(
            () -> platform(() -> service.updatePlatform(version, Map.of("password_min_chars", 18))))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(base()).isEqualTo(version);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='platform.security.policy.updated' AND result='FAILURE'",
                Integer.class,
                platform))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND details::text LIKE '%DO_NOT_ECHO_POLICY_STORAGE%'",
                Integer.class, platform))
        .isZero();
  }

  @Test
  void allEditableHardBoundsPersistWithoutChangingFixedPasswordRules() {
    Map<String, Integer> tight =
        Map.of(
            "password_min_chars",
            32,
            "login_failure_threshold",
            1,
            "login_failure_window_seconds",
            3600,
            "login_lock_seconds",
            3600,
            "login_ip_attempt_limit",
            1,
            "login_ip_window_seconds",
            300,
            "session_absolute_seconds",
            300,
            "session_idle_seconds",
            60,
            "recent_auth_seconds",
            60);
    var saved = platform(() -> service.updatePlatform(base(), tight));
    assertThat(saved.values()).isEqualTo(tight);
    assertThat(values()).isEqualTo(tight);
    var fixed =
        jdbc.queryForMap(
            "SELECT password_max_chars,password_max_utf8_bytes,password_blocklist_enabled,cardinality(password_required_character_classes) AS classes FROM sys_security_policy WHERE owner_type='PLATFORM'");
    assertThat(fixed)
        .containsEntry("password_max_chars", 64)
        .containsEntry("password_max_utf8_bytes", 72)
        .containsEntry("password_blocklist_enabled", true)
        .containsEntry("classes", 0);
  }

  @Test
  void failureAuditOutageIs503AndNeverWritesPolicy() {
    long version = base();
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_POLICY_AUDIT"))
        .when(audit)
        .failure(any(), any(), any(), any(), any(), any(), any());
    var error =
        catchThrowable(
            () -> platform(() -> service.updatePlatform(version, Map.of("password_min_chars", 1))));
    code(error, "SECURITY_AUDIT_UNAVAILABLE", 503);
    assertThat(error.getMessage()).doesNotContain("DO_NOT_ECHO_POLICY_AUDIT");
    assertThat(base()).isEqualTo(version);
  }
}
