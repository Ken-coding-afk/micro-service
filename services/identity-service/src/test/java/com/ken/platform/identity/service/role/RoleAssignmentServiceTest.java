package com.ken.platform.identity.service.role;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.role.RoleAssignmentMapper;
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

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class RoleAssignmentServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private RoleAssignmentService service;
  private UUID tenant;
  private UUID otherTenantUser;
  private final UUID actor = UUID.randomUUID();
  private final UUID secondAdmin = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private final UUID adminRole = UUID.randomUUID();
  private final UUID firstRole = UUID.randomUUID();
  private final UUID secondRole = UUID.randomUUID();

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
    otherTenantUser = UUID.fromString(binding.get("user_b").asString());
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
    mybatis.addMapper(RoleAssignmentMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    var template = new SqlSessionTemplate(factory.getObject());
    var users = template.getMapper(UserManagementMapper.class);
    service =
        new RoleAssignmentService(
            transactions,
            users,
            template.getMapper(RolePermissionMapper.class),
            template.getMapper(RoleAssignmentMapper.class),
            new TenantManagementAuthorization(users),
            Clock.systemUTC(),
            audits());
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('identity:role:assign','分配角色','identity-service','TENANT','identity','身份','role','角色','assign',ARRAY['TENANT'],'identity.roles') ON CONFLICT DO NOTHING");
          for (UUID user : List.of(actor, secondAdmin, target)) {
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES (?,?,?,'测试用户','{bcrypt}fixture','ACTIVE',false)",
                user,
                tenant,
                "test-" + user);
          }
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES (?,?,'tenant-admin','管理员',true)",
              adminRole,
              tenant);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'identity:role:assign','TENANT')",
              tenant,
              adminRole);
          for (UUID user : List.of(actor, secondAdmin)) {
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
                tenant,
                user,
                adminRole);
          }
          for (UUID role : List.of(firstRole, secondRole)) {
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,?,'普通角色')",
                role,
                tenant,
                "test-" + role);
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
            for (UUID user : List.of(actor, secondAdmin, target)) {
              jdbc.update(
                  "DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?", tenant, user);
            }
            for (UUID role : List.of(adminRole, firstRole, secondRole)) {
              jdbc.update(
                  "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?", tenant, role);
              jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, role);
            }
            for (UUID user : List.of(actor, secondAdmin, target)) {
              jdbc.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", tenant, user);
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
            new CallContext("test-member-data", tenant, actor.toString(), null, null, null))) {
      return transactions.execute(action);
    }
  }

  private <T> T authorized(UUID user, Supplier<T> action) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_identity:role:assign"));
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
            new CallContext("test-member", tenant, user.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private RoleAssignmentService.Result assign(
      long version, List<RoleAssignmentService.RoleReference> roles) {
    return authorized(actor, () -> service.replace(target, version, roles));
  }

  @Test
  void memberChangesAdvanceReferenceVersionWithoutInvalidatingFrozenRoleAuthorization() {
    var assigned = assign(0, List.of(new RoleAssignmentService.RoleReference(firstRole, 0)));
    assertThat(assigned.version()).isEqualTo(1);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, firstRole)))
        .isEqualTo(1);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT authorization_version FROM sys_role WHERE id=?",
                        Long.class,
                        firstRole)))
        .isZero();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, target)))
        .isZero();
    assertThat(assign(1, List.of(new RoleAssignmentService.RoleReference(firstRole, 1))).version())
        .isEqualTo(1);
    assertThat(assign(1, List.of()).version()).isEqualTo(2);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT authorization_version FROM sys_role WHERE id=?",
                        Long.class,
                        firstRole)))
        .isZero();
  }

  @Test
  void staleViewsAndOtherTenantTargetAreRejected() {
    assertThatThrownBy(
            () -> assign(0, List.of(new RoleAssignmentService.RoleReference(firstRole, 99))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThatThrownBy(
            () -> assign(99, List.of(new RoleAssignmentService.RoleReference(firstRole, 0))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThatThrownBy(
            () ->
                authorized(
                    actor,
                    () ->
                        service.replace(
                            otherTenantUser,
                            0,
                            List.of(new RoleAssignmentService.RoleReference(firstRole, 0)))))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(404));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_user_role WHERE user_id=?",
                        Integer.class,
                        target)))
        .isZero();
  }

  @Test
  void removingAdministratorRoleCannotRemoveLastEffectiveAdministrator() {
    authorized(actor, () -> service.replace(actor, 0, List.of()));
    assertThatThrownBy(
            () -> authorized(secondAdmin, () -> service.replace(secondAdmin, 0, List.of())))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error -> assertThat(((PlatformException) error).code()).isEqualTo("LAST_TENANT_ADMIN"));
  }

  @Test
  void disabledMembershipCanBeRetainedOrRemovedButNotGrantedAgain() {
    data(
        () -> {
          jdbc.update(
              "UPDATE sys_role SET status='DISABLED',version=version+1,authorization_version=authorization_version+1 WHERE id=?",
              firstRole);
          return null;
        });
    assertThatThrownBy(
            () -> assign(0, List.of(new RoleAssignmentService.RoleReference(firstRole, 1))))
        .isInstanceOf(PlatformException.class);
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
              tenant,
              target,
              firstRole);
          return null;
        });
    assertThat(assign(0, List.of(new RoleAssignmentService.RoleReference(firstRole, 1))).version())
        .isZero();
    assertThat(assign(0, List.of()).version()).isEqualTo(1);
    assertThatThrownBy(
            () -> assign(1, List.of(new RoleAssignmentService.RoleReference(firstRole, 2))))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void concurrentAssignmentsDoNotSilentlyReplaceEachOther() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var pending =
          List.of(firstRole, secondRole).stream()
              .map(
                  role ->
                      workers.submit(
                          () -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            try {
                              assign(0, List.of(new RoleAssignmentService.RoleReference(role, 0)));
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
                        "SELECT count(*) FROM sys_user_role WHERE user_id=?",
                        Integer.class,
                        target)))
        .isEqualTo(1);
  }

  @Test
  void failedMembershipWriteKeepsAuditWithoutChangingTargetVersion() {
    assertThatThrownBy(() -> assign(999, List.of())).isInstanceOf(PlatformException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE target_id=? AND action='identity.user.roles' AND result='FAILURE' AND actor_id=?",
                Integer.class,
                target.toString(),
                actor))
        .isEqualTo(1);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_user WHERE id=?", Long.class, target)))
        .isZero();
  }
}
