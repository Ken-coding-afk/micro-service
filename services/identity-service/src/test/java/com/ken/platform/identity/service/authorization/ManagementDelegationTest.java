package com.ken.platform.identity.service.authorization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.role.RoleAssignmentMapper;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.role.RoleAssignmentService;
import com.ken.platform.identity.service.role.RolePermissionService;
import com.ken.platform.identity.service.user.UserProfileService;
import com.ken.platform.identity.service.user.UserStatusService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 真实 PG/MyBatis/完整管理事务，主体为已认证技术夹具；不代替 HTTP/OAuth 验收。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class ManagementDelegationTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private RoleAssignmentService assignments;
  private RolePermissionService permissions;
  private UserStatusService statuses;
  private UserProfileService profiles;
  private UserManagementMapper profileUsers;
  private final UUID tenant = UUID.randomUUID(),
      admin = UUID.randomUUID(),
      operator = UUID.randomUUID(),
      target = UUID.randomUUID(),
      extra = UUID.randomUUID(),
      adminRole = UUID.randomUUID(),
      operatorRole = UUID.randomUUID(),
      role = UUID.randomUUID(),
      secondRole = UUID.randomUUID();
  private final String read = "delegated:read:" + UUID.randomUUID(),
      submit = "delegated:submit:" + UUID.randomUUID();
  private static final List<String> OPERATIONS =
      List.of("identity:role:assign", "identity:role:manage", "identity:user:manage");

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
    config.setMaximumPoolSize(8);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    var manager = new JdbcTransactionManager(pool);
    transactions = new TenantTransactions(jdbc, manager);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    for (var mapper :
        List.of(UserManagementMapper.class, RolePermissionMapper.class, RoleAssignmentMapper.class))
      configuration.addMapper(mapper);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var sql = new SqlSessionTemplate(factory.getObject());
    var users = sql.getMapper(UserManagementMapper.class);
    var roles = sql.getMapper(RolePermissionMapper.class);
    var authorization = new TenantManagementAuthorization(users);
    var audits = new ManagementAudits(new SecurityAuditRepository(jdbc), manager);
    profileUsers = spy(users);
    profiles =
        new UserProfileService(
            transactions, profileUsers, authorization, audits, Clock.systemUTC());
    assignments =
        new RoleAssignmentService(
            transactions,
            users,
            roles,
            sql.getMapper(RoleAssignmentMapper.class),
            authorization,
            Clock.systemUTC(),
            audits);
    permissions =
        new RolePermissionService(
            transactions, users, roles, authorization, Clock.systemUTC(), audits);
    statuses = new UserStatusService(transactions, users, authorization, Clock.systemUTC(), audits);
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'固定管理边界测试')",
        tenant,
        "delegated-" + tenant);
    data(
        () -> {
          for (UUID user : List.of(admin, operator, target, extra))
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'测试用户','{bcrypt}fixture','ACTIVE',false)",
                user,
                tenant,
                "u-" + user);
          for (String operation : OPERATIONS) {
            String[] parts = operation.split(":");
            jdbc.update(
                "INSERT INTO sys_permission(source,code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('PUBLISHED',?,'身份管理','identity-service','TENANT','identity','身份',?,'管理资源',?,ARRAY['TENANT'],?) ON CONFLICT DO NOTHING",
                operation,
                parts[1],
                parts[2],
                parts[1].equals("user") ? "identity.users" : "identity.roles");
          }
          for (UUID id : List.of(adminRole, operatorRole, role, secondRole))
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,?,'测试角色',?)",
                id,
                tenant,
                id.equals(adminRole) ? "tenant-admin" : "r-" + id,
                id.equals(adminRole));
          for (String operation : OPERATIONS)
            for (UUID id : List.of(adminRole, operatorRole))
              jdbc.update(
                  "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
                  tenant,
                  id,
                  operation);
          member(admin, adminRole);
          member(operator, operatorRole);
          for (String code : List.of(read, submit))
            jdbc.update(
                "INSERT INTO sys_permission(source,code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('PUBLISHED',?,'委派业务','document-service','TENANT',?,'测试',?,'测试资源',?,?::text[],?)",
                code,
                "d-" + tenant,
                "r-" + code,
                code.equals(read) ? "read" : "submit",
                code.equals(read) ? "{SELF,TENANT}" : "{}",
                "delegated." + code.replace(':', '.'));
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'SELF')",
              tenant,
              role,
              read);
          return null;
        });
  }

  @AfterEach
  void close() {
    SecurityContextHolder.clearContext();
    if (pool != null) pool.close();
  }

  private <T> T data(Supplier<T> action) {
    if (CallContexts.current() != null) return transactions.execute(action);
    try (var scope =
        CallContexts.bind(
            new CallContext("delegated-data", tenant, admin.toString(), null, null, null))) {
      return transactions.execute(action);
    }
  }

  private <T> T authorized(UUID user, Supplier<T> action) {
    List<GrantedAuthority> authorities =
        OPERATIONS.stream()
            .<GrantedAuthority>map(code -> new SimpleGrantedAuthority("PERMISSION_" + code))
            .toList();
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            user.toString(),
            Map.of("principal_type", "user", "tenant_id", tenant.toString()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var scope =
        CallContexts.bind(
            new CallContext(
                "delegated-" + UUID.randomUUID(), tenant, user.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void member(UUID user, UUID id) {
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)", tenant, user, id);
  }

  private long roleVersion() {
    return data(
        () -> jdbc.queryForObject("SELECT version FROM sys_role WHERE id=?", Long.class, role));
  }

  private long userVersion(UUID user) {
    return data(
        () -> jdbc.queryForObject("SELECT version FROM sys_user WHERE id=?", Long.class, user));
  }

  private long catalog() {
    return jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class);
  }

  private void legacyGrant(boolean assign, boolean edit) {
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_management_grant(tenant_id,actor_user_id,role_id,allow_assign,allow_edit) VALUES(?,?,?,?,?)",
              tenant,
              operator,
              role,
              assign,
              edit);
          jdbc.update(
              "INSERT INTO sys_management_grant_permission(tenant_id,actor_user_id,role_id,permission_code,max_data_scope) VALUES(?,?,?,?,'TENANT')",
              tenant,
              operator,
              role,
              read);
          return null;
        });
  }

  private void denied(Supplier<?> action) {
    assertThatThrownBy(() -> authorized(operator, action))
        .isInstanceOfSatisfying(
            PlatformException.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(403);
              assertThat(failure.code()).isEqualTo("MANAGEMENT_NOT_ALLOWED");
            });
  }

  @Test
  void legacyAssignmentGrantNeverBecomesAnAdministrator() {
    legacyGrant(true, false);
    long before = userVersion(target);
    denied(
        () ->
            assignments.replace(
                target,
                before,
                List.of(new RoleAssignmentService.RoleReference(role, roleVersion()))));
    assertThat(userVersion(target)).isEqualTo(before);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                        Integer.class,
                        target,
                        role)))
        .isZero();
  }

  @Test
  void legacyEditingCeilingCannotAuthorizeRoleChanges() {
    legacyGrant(false, true);
    long before = roleVersion();
    denied(
        () ->
            permissions.replace(
                role,
                before,
                catalog(),
                List.of(new RolePermissionService.Grant(read, DataScope.TENANT))));
    assertThat(roleVersion()).isEqualTo(before);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT data_scope FROM sys_role_permission WHERE role_id=? AND permission_code=?",
                        String.class,
                        role,
                        read)))
        .isEqualTo("SELF");
  }

  @Test
  void oldBusinessManagementPermissionsDoNotAuthorizeAccountMutations() {
    legacyGrant(true, true);
    long before = userVersion(target);
    denied(() -> statuses.changeStatus(target, UserStatusService.Status.DISABLED, before));
    denied(
        () ->
            profiles.update(
                target,
                before,
                new UserProfileService.Fields("不能修改", null, null, null, null, null, null)));
    assertThat(userVersion(target)).isEqualTo(before);
  }

  @Test
  void administratorStillAssignsAndEditsWithCurrentVersions() {
    var assigned =
        authorized(
            admin,
            () ->
                assignments.replace(
                    target,
                    userVersion(target),
                    List.of(new RoleAssignmentService.RoleReference(role, roleVersion()))));
    assertThat(assigned.roles()).containsExactly(role);
    var edited =
        authorized(
            admin,
            () ->
                permissions.replace(
                    role,
                    roleVersion(),
                    catalog(),
                    List.of(new RolePermissionService.Grant(read, DataScope.TENANT))));
    assertThat(edited.grants())
        .containsExactly(new RolePermissionService.Grant(read, DataScope.TENANT));
  }

  @Test
  void administratorCannotUseForeignTargetOrStaleRoleVersion() {
    assertThatThrownBy(
            () -> authorized(admin, () -> assignments.replace(UUID.randomUUID(), 0, List.of())))
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(
            () ->
                authorized(
                    admin,
                    () -> permissions.replace(role, roleVersion() + 1, catalog(), List.of())))
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.status()).isEqualTo(409));
  }

  @Test
  void changedAdministratorStateIsRecheckedAfterAuthentication() {
    data(
        () -> {
          jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE id=?", adminRole);
          return null;
        });
    assertThatThrownBy(
            () ->
                authorized(
                    admin, () -> assignments.replace(target, userVersion(target), List.of())))
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.code()).isEqualTo("MANAGEMENT_NOT_ALLOWED"));
  }
}
