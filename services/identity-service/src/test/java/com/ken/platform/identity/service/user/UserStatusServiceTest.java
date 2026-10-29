package com.ken.platform.identity.service.user;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import tools.jackson.databind.json.JsonMapper;

/** 仅验证服务事务规则；合成已验证 principal 不代表 OAuth/HTTP 认证通过。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class UserStatusServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private UserManagementMapper mapper;
  private UserStatusService service;
  private UUID tenant;
  private UUID first = UUID.randomUUID();
  private UUID second = UUID.randomUUID();
  private UUID ordinary = UUID.randomUUID();
  private UUID role = UUID.randomUUID();

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
    tenant = UUID.fromString(binding.get("tenant_a").asString());
    var configuration = new HikariConfig();
    configuration.setJdbcUrl(
        "jdbc:postgresql://"
            + binding.get("host").asString()
            + ":"
            + binding.get("port").asInt()
            + "/"
            + binding.get("dbname").asString());
    configuration.setUsername(binding.get("username").asString());
    configuration.setPassword(
        Files.readString(Path.of(binding.get("password_file").asString())).strip());
    configuration.setMaximumPoolSize(4);
    pool = new HikariDataSource(configuration);
    jdbc = new JdbcTemplate(pool);
    transactions = new TenantTransactions(jdbc, new JdbcTransactionManager(pool));
    var mybatis = new Configuration();
    mybatis
        .getTypeHandlerRegistry()
        .register(UUID.class, com.ken.platform.foundation.data.UuidTypeHandler.class);
    mybatis.addMapper(UserManagementMapper.class);
    mybatis.addMapper(RolePermissionMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    var template = new SqlSessionTemplate(factory.getObject());
    mapper = template.getMapper(UserManagementMapper.class);
    service =
        new UserStatusService(
            transactions,
            mapper,
            new com.ken.platform.identity.service.authorization.TenantManagementAuthorization(
                mapper),
            Clock.systemUTC(),
            audits());
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_permission(source,code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('PUBLISHED','identity:user:manage','管理用户','identity-service','TENANT','identity','身份','user','用户','manage',ARRAY['TENANT'],'identity.users') ON CONFLICT DO NOTHING");
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES (?,?,'tenant-admin','租户管理员',true)",
              role,
              tenant);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'identity:user:manage','TENANT')",
              tenant,
              role);
          for (UUID id : List.of(first, second, ordinary)) {
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES (?,?,?,'测试用户','{bcrypt}fixture','ACTIVE',false)",
                id,
                tenant,
                "test-" + id);
          }
          for (UUID id : List.of(first, second)) {
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
                tenant,
                id,
                role);
          }
          return null;
        });
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (pool == null) {
      return;
    }
    try {
      data(
          () -> {
            jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=? AND role_id=?", tenant, role);
            jdbc.update(
                "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?", tenant, role);
            jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, role);
            for (UUID id : List.of(first, second, ordinary)) {
              jdbc.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", tenant, id);
            }
            return null;
          });
    } finally {
      pool.close();
    }
  }

  private <T> T data(Supplier<T> action) {
    try (var ignored =
        CallContexts.bind(
            new CallContext("test-data", tenant, first.toString(), null, null, null))) {
      return transactions.execute(action);
    }
  }

  private <T> T asUser(UUID user, boolean allowedByToken, Supplier<T> action) {
    List<org.springframework.security.core.GrantedAuthority> authorities =
        allowedByToken
            ? List.of(new SimpleGrantedAuthority("PERMISSION_identity:user:manage"))
            : List.of();
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            user.toString(),
            Map.of(
                "sub", user.toString(), "tenant_id", tenant.toString(), "principal_type", "user"),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext("test-status", tenant, user.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void statusRevocationVersionAndAuditCommitTogetherAndStaleUpdateDoesNotWrite() {
    var disabled =
        asUser(
            first,
            true,
            () -> service.changeStatus(ordinary, UserStatusService.Status.DISABLED, 0));
    assertThat(disabled.version()).isEqualTo(1);
    assertThat(disabled.authVersion()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                asUser(
                    first,
                    true,
                    () -> service.changeStatus(ordinary, UserStatusService.Status.ACTIVE, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error ->
                assertThat(((PlatformException) error).code()).isEqualTo("USER_VERSION_CONFLICT"));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_auth_audit WHERE result='SUCCESS' AND target_id=?",
                        Integer.class,
                        ordinary.toString())))
        .isEqualTo(1);
    var restored =
        asUser(
            first, true, () -> service.changeStatus(ordinary, UserStatusService.Status.ACTIVE, 1));
    assertThat(restored.authVersion()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE target_id=? AND result='FAILURE' AND details->>'error_code'='USER_VERSION_CONFLICT'",
                Integer.class,
                ordinary.toString()))
        .isEqualTo(1);
  }

  @Test
  void applicationRestrictionAndUnprotectedActorCannotManageAccounts() {
    assertThatThrownBy(
            () ->
                asUser(
                    first,
                    false,
                    () -> service.changeStatus(ordinary, UserStatusService.Status.DISABLED, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(403));
    assertThatThrownBy(
            () ->
                asUser(
                    ordinary,
                    true,
                    () -> service.changeStatus(first, UserStatusService.Status.DISABLED, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(403));
    assertThat(data(() -> mapper.countEffectiveAdministrators(tenant))).isEqualTo(2);
  }

  @Test
  void failedOutboxWriteRollsBackStatusRevocationAndSuccessfulAudit() {
    var failing =
        org.mockito.Mockito.mock(
            UserManagementMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
    org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable"))
        .when(failing)
        .statusEvent(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.anyString());
    var guarded =
        new UserStatusService(
            transactions,
            failing,
            new com.ken.platform.identity.service.authorization.TenantManagementAuthorization(
                failing),
            Clock.systemUTC(),
            audits());
    assertThatThrownBy(
            () ->
                asUser(
                    first,
                    true,
                    () -> guarded.changeStatus(ordinary, UserStatusService.Status.DISABLED, 0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("outbox unavailable");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT status FROM sys_user WHERE id=?", String.class, ordinary)))
        .isEqualTo("ACTIVE");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, ordinary)))
        .isZero();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_auth_audit WHERE result='SUCCESS' AND target_id=?",
                        Integer.class,
                        ordinary.toString())))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE target_id=? AND result='FAILURE' AND details->>'error_code'='INTERNAL_ERROR'",
                Integer.class,
                ordinary.toString()))
        .isEqualTo(1);
  }

  @Test
  void concurrentSelfDisableKeepsExactlyOneEffectiveAdministrator() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var results =
          List.of(first, second).stream()
              .map(
                  user ->
                      workers.submit(
                          () -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            try {
                              asUser(
                                  user,
                                  true,
                                  () ->
                                      service.changeStatus(
                                          user, UserStatusService.Status.DISABLED, 0));
                              return true;
                            } catch (PlatformException rejected) {
                              assertThat(rejected.code()).isEqualTo("LAST_TENANT_ADMIN");
                              return false;
                            }
                          }))
              .toList();
      assertThat(
              List.of(
                  results.get(0).get(10, TimeUnit.SECONDS),
                  results.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(data(() -> mapper.countEffectiveAdministrators(tenant))).isEqualTo(1);
  }
}
