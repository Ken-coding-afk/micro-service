package com.ken.platform.identity.service.role;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import tools.jackson.databind.json.JsonMapper;

/** 已验证 principal 夹具 + 真实 PG/MyBatis；不代替 OAuth/BFF 验收。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class RolePermissionServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private RolePermissionService service;
  private UUID tenant;
  private final UUID actor = UUID.randomUUID();
  private final UUID adminRole = UUID.randomUUID();
  private final UUID role = UUID.randomUUID();
  private long catalogVersion;

  private static RolePermissionService.Grant read(DataScope scope) {
    return new RolePermissionService.Grant("document:read", scope);
  }

  private static RolePermissionService.Grant submit() {
    return new RolePermissionService.Grant("test:submit", null);
  }

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
    mybatis.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    mybatis.addMapper(UserManagementMapper.class);
    mybatis.addMapper(RolePermissionMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    var template = new SqlSessionTemplate(factory.getObject());
    var users = template.getMapper(UserManagementMapper.class);
    service =
        new RolePermissionService(
            transactions,
            users,
            template.getMapper(RolePermissionMapper.class),
            new TenantManagementAuthorization(users),
            Clock.systemUTC(),
            audits());
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('identity:role:manage','管理角色','identity-service','TENANT','identity','身份','role','角色','manage',ARRAY['TENANT'],'identity.roles') ON CONFLICT DO NOTHING");
          jdbc.update(
              "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('test:submit','提交','document-service','TENANT','test','测试','test-submit','测试提交','submit','{}','documents.submit') ON CONFLICT (code) DO UPDATE SET status='ACTIVE'");
          jdbc.update(
              "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES (?,?,?,'测试管理员','{bcrypt}fixture','ACTIVE',false)",
              actor,
              tenant,
              "test-" + actor);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES (?,?,'tenant-admin','管理员',true)",
              adminRole,
              tenant);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,?,'测试角色')",
              role,
              tenant,
              "test-" + role);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'identity:role:manage','TENANT')",
              tenant,
              adminRole);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'document:read','SELF')",
              tenant,
              role);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
              tenant,
              actor,
              adminRole);
          return null;
        });
    catalogVersion =
        jdbc.queryForObject(
            "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class);
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
            jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?", tenant, actor);
            for (UUID id : List.of(role, adminRole)) {
              jdbc.update(
                  "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?", tenant, id);
              jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, id);
            }
            jdbc.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", tenant, actor);
            jdbc.update("UPDATE sys_permission SET status='ACTIVE' WHERE code='test:submit'");
            return null;
          });
    } finally {
      pool.close();
    }
  }

  private <T> T data(Supplier<T> action) {
    try (var ignored =
        CallContexts.bind(
            new CallContext("test-role-data", tenant, actor.toString(), null, null, null))) {
      return transactions.execute(action);
    }
  }

  private <T> T authorized(Supplier<T> action) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_identity:role:manage"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            Map.of(
                "sub", actor.toString(), "tenant_id", tenant.toString(), "principal_type", "user"),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext("test-role", tenant, actor.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private RolePermissionService.Result replace(
      long version, List<RolePermissionService.Grant> values) {
    return authorized(() -> service.replace(role, version, catalogVersion, values));
  }

  private String readScope() {
    return data(
        () ->
            jdbc.queryForObject(
                "SELECT data_scope FROM sys_role_permission WHERE role_id=? AND permission_code='document:read'",
                String.class,
                role));
  }

  @Test
  void ordinaryRoleCannotGainIdentityAdministration() {
    assertThatThrownBy(
            () ->
                replace(
                    0,
                    List.of(
                        read(DataScope.SELF),
                        new RolePermissionService.Grant("identity:role:manage", DataScope.TENANT))))
        .isInstanceOfSatisfying(
            PlatformException.class,
            error -> assertThat(error.code()).isEqualTo("ROLE_PERMISSION_INVALID"));
    assertThat(readScope()).isEqualTo("SELF");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, role)))
        .isZero();
  }

  @Test
  void realGrantReplacementIncrementsSeparateVersionsAndNoopDoesNot() {
    var changed = replace(0, List.of(read(DataScope.TENANT), submit()));
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.authorizationVersion()).isEqualTo(1);
    assertThat(readScope()).isEqualTo("TENANT");
    assertThat(replace(1, List.of(submit(), read(DataScope.TENANT))).version()).isEqualTo(1);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_auth_audit WHERE target_id=?",
                        Integer.class,
                        role.toString())))
        .isEqualTo(1);
    var removed = replace(1, List.of(submit()));
    assertThat(removed.version()).isEqualTo(2);
    assertThat(removed.grants()).containsExactly(submit());
  }

  @Test
  void invalidBatchIsRejectedEntirelyAndNullIsNotTenantScope() {
    assertThatThrownBy(
            () ->
                replace(
                    0,
                    List.of(
                        read(DataScope.TENANT),
                        new RolePermissionService.Grant("unknown:grant", null))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(400));
    assertThat(readScope()).isEqualTo("SELF");
    assertThatThrownBy(() -> replace(0, List.of(read(null)))).isInstanceOf(PlatformException.class);
    assertThatThrownBy(() -> replace(0, List.of(submit(), submit())))
        .isInstanceOf(PlatformException.class);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, role)))
        .isZero();
  }

  @Test
  void versionConflictAndProtectedRoleAreRejectedBeforeWriting() {
    assertThatThrownBy(() -> replace(99, List.of(read(DataScope.TENANT))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    jdbc.update("UPDATE sys_permission_catalog_state SET version=version+1 WHERE id=1");
    assertThatThrownBy(() -> replace(0, List.of(read(DataScope.TENANT))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThatThrownBy(
            () -> authorized(() -> service.replace(adminRole, 0, catalogVersion + 1, List.of())))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error -> assertThat(((PlatformException) error).code()).isEqualTo("PROTECTED_ROLE"));
    assertThat(readScope()).isEqualTo("SELF");
  }

  @Test
  void historicalDisabledGrantCanBeRetainedOrRemovedButNeverGrantedAgain() {
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'test:submit',NULL)",
              tenant,
              role);
          jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE code='test:submit'");
          return null;
        });
    assertThat(replace(0, List.of(read(DataScope.TENANT), submit())).version()).isEqualTo(1);
    assertThat(replace(1, List.of(read(DataScope.TENANT))).version()).isEqualTo(2);
    assertThatThrownBy(() -> replace(2, List.of(read(DataScope.TENANT), submit())))
        .isInstanceOf(PlatformException.class);
    assertThat(readScope()).isEqualTo("TENANT");
  }

  @Test
  void twoConcurrentEditsCannotOverwriteStaleRoleView() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var candidates =
          List.of(List.of(read(DataScope.TENANT)), List.of(read(DataScope.SELF), submit()));
      var pending =
          candidates.stream()
              .map(
                  values ->
                      workers.submit(
                          () -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            try {
                              replace(0, values);
                              return true;
                            } catch (PlatformException rejected) {
                              assertThat(rejected.status()).isEqualTo(409);
                              return false;
                            }
                          }))
              .toList();
      assertThat(
              List.of(
                  pending.get(0).get(10, TimeUnit.SECONDS),
                  pending.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, role)))
        .isEqualTo(1);
  }

  @Test
  void failedPermissionWriteRetainsIndependentAuditAfterRollback() {
    assertThatThrownBy(() -> replace(999, List.of())).isInstanceOf(PlatformException.class);
    var event =
        jdbc.queryForMap(
            "SELECT result,actor_type,tenant_id,details::text AS details FROM sys_auth_audit WHERE target_id=? AND action='identity.role.permissions' AND actor_id=?",
            role.toString(),
            actor);
    assertThat(event.get("result")).isEqualTo("FAILURE");
    assertThat(event.get("actor_type")).isEqualTo("TENANT");
    assertThat(event.get("tenant_id")).isEqualTo(tenant);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, role)))
        .isZero();
  }
}
