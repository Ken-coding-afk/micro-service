package com.ken.platform.identity.service.group;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.*;
import com.ken.platform.foundation.http.*;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.authorization.AuthorizationMapper;
import com.ken.platform.identity.dal.mapper.group.UserGroupRepository;
import com.ken.platform.identity.dal.mapper.group.UserGroupRepository.Group;
import com.ken.platform.identity.dal.mapper.login.*;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.dal.mapper.role.RoleManagementMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.*;
import com.ken.platform.identity.service.group.UserGroupService.Reference;
import com.ken.platform.identity.service.oauth.*;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.*;
import com.zaxxer.hikari.*;
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

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class UserGroupServiceTest {
  HikariDataSource pool;
  JdbcTemplate jdbc;
  JdbcTransactionManager manager;
  IdentityTenantScope scope;
  TenantTransactions tenants;
  TenantManagementAuthorization authorization;
  UserManagementMapper users;
  AccountManagementMapper accounts;
  UserGroupRepository repository;
  AuthorizationMapper grants;
  PermissionCatalogMapper catalog;
  RoleManagementMapper auditRows;
  UserGroupService service;
  Clock clock = Clock.systemUTC();
  final UUID tenant = UUID.randomUUID(),
      other = UUID.randomUUID(),
      user = UUID.randomUUID(),
      otherUser = UUID.randomUUID(),
      actor = UUID.randomUUID(),
      role = UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    var b =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(b.path("test_only").asBoolean()).isTrue();
    assertThat(b.path("dbname").asString()).startsWith("t02t");
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + b.path("host").asString()
            + ":"
            + b.path("port").asInt()
            + "/"
            + b.path("dbname").asString());
    config.setUsername(b.path("username").asString());
    config.setPassword(Files.readString(Path.of(b.path("password_file").asString())).strip());
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
            UserManagementMapper.class,
            AccountManagementMapper.class,
            RoleManagementMapper.class,
            AuthorizationMapper.class,
            PermissionCatalogMapper.class)) configuration.addMapper(type);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    var sql = new SqlSessionTemplate(factory.getObject());
    users = sql.getMapper(UserManagementMapper.class);
    accounts = sql.getMapper(AccountManagementMapper.class);
    grants = sql.getMapper(AuthorizationMapper.class);
    catalog = sql.getMapper(PermissionCatalogMapper.class);
    auditRows = sql.getMapper(RoleManagementMapper.class);
    new BuiltinPermissionCatalog(catalog, manager).install();
    authorization =
        new TenantManagementAuthorization(
            users, new PlatformManagementAuthorization(accounts), scope, manager);
    repository = new UserGroupRepository(jdbc);
    service =
        new UserGroupService(
            repository,
            users,
            sql.getMapper(RoleManagementMapper.class),
            accounts,
            authorization,
            tenants,
            new ManagementAudits(new SecurityAuditRepository(jdbc), manager),
            clock);
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,'管理员','unused','ACTIVE',false)",
        actor,
        "group-" + actor);
    for (UUID id : List.of(tenant, other)) {
      jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'组织测试')", id, "group-" + id);
      data(
          id,
          () ->
              jdbc.update(
                  "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'成员','unused','ACTIVE',false)",
                  id.equals(tenant) ? user : otherUser,
                  id,
                  "member-" + id));
    }
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'reader','阅读员')",
                role,
                tenant));
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
    if (pool != null) pool.close();
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
        List.of(
            "platform:identity:read",
            "platform:identity:manage",
            "platform:permission:read",
            "platform:permission:manage"),
        clock.instant(),
        "identity-admin-platform",
        action);
  }

  private Group create(String code, UUID parent) {
    return platform(
        () ->
            service.create(
                code,
                code,
                "",
                parent,
                0,
                data(tenant, () -> repository.organizationVersion(tenant))));
  }

  private Group get(UUID id) {
    return platform(() -> service.get(id));
  }

  private void failure(String expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  @Test
  void treeMoveRejectsCyclesAndCrossTenantParentsAndDeletionRequiresEmptyGroup() {
    var company = create("company", null);
    var team = create("team", company.id());
    var fresh = get(company.id());
    failure(
        "GROUP_CYCLE",
        () ->
            platform(
                () ->
                    service.move(
                        fresh.id(), fresh.version(), fresh.organizationVersion(), team.id(), 0)));
    failure(
        "GROUP_NOT_EMPTY",
        () ->
            platform(
                () -> service.delete(fresh.id(), fresh.version(), fresh.organizationVersion())));
    var foreign =
        platform(
            other,
            UUID.randomUUID().toString(),
            List.of("platform:identity:read", "platform:identity:manage"),
            clock.instant(),
            "external-admin",
            () -> service.create("foreign", "Foreign", "", null, 0, 0L));
    failure(
        "NOT_FOUND",
        () ->
            platform(
                () ->
                    service.move(
                        fresh.id(),
                        fresh.version(),
                        fresh.organizationVersion(),
                        foreign.id(),
                        0)));
    var moved =
        platform(
            () -> service.move(team.id(), team.version(), fresh.organizationVersion(), null, 0));
    assertThat(moved.parentId()).isNull();
    var empty = get(company.id());
    platform(() -> service.delete(empty.id(), empty.version(), empty.organizationVersion()));
    failure("NOT_FOUND", () -> get(company.id()));
  }

  @Test
  void authorizationUsesOnlyDirectGroupMembershipAndAncestorDisableRevokesThatSource() {
    var company = create("company", null);
    var team = create("team", company.id());
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,'document:read','SELF')",
                tenant,
                role));
    platform(
        () ->
            service.replaceRoles(
                company.id(), company.version(), List.of(new Reference(role, 0L))));
    var member =
        platform(
            () ->
                service.changeMembers(
                    team.id(), team.version(), List.of(new Reference(user, 0L)), true));
    assertThat(data(tenant, () -> grants.grants(tenant, user))).isEmpty();
    long roleVersion =
        data(
            tenant,
            () -> jdbc.queryForObject("SELECT version FROM sys_role WHERE id=?", Long.class, role));
    platform(
        () ->
            service.replaceRoles(
                team.id(), member.version(), List.of(new Reference(role, roleVersion))));
    assertThat(data(tenant, () -> grants.grants(tenant, user))).hasSize(1);
    assertThat(data(tenant, () -> grants.origins(tenant, user)))
        .extracting(AuthorizationMapper.Origin::groupPath)
        .containsExactly("company / team");
    var parent = get(company.id());
    platform(
        () ->
            service.update(
                parent.id(),
                parent.version(),
                parent.organizationVersion(),
                parent.name(),
                "",
                0,
                "DISABLED"));
    assertThat(get(team.id()).effectiveStatus()).isEqualTo("DISABLED");
    assertThat(data(tenant, () -> grants.grants(tenant, user))).isEmpty();
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                user,
                role));
    assertThat(data(tenant, () -> grants.grants(tenant, user))).hasSize(1);
    assertThat(data(tenant, () -> grants.origins(tenant, user)))
        .extracting(AuthorizationMapper.Origin::sourceType)
        .containsExactly("DIRECT");
    assertThat(data(other, () -> grants.grants(tenant, user))).isEmpty();
  }

  @Test
  void memberBatchIsAtomicAndProtectedRolesCannotBeAssignedToGroups() {
    var group = create("team", null);
    failure(
        "NOT_FOUND",
        () ->
            platform(
                () ->
                    service.changeMembers(
                        group.id(),
                        group.version(),
                        List.of(new Reference(user, 0L), new Reference(otherUser, 0L)),
                        true)));
    assertThat(get(group.id()).memberCount()).isZero();
    UUID adminRole = UUID.randomUUID();
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,'tenant-admin','管理员',true)",
                adminRole,
                tenant));
    failure(
        "PROTECTED_ROLE",
        () ->
            platform(
                () ->
                    service.replaceRoles(
                        group.id(), group.version(), List.of(new Reference(adminRole, 0L)))));
    var added =
        platform(
            () ->
                service.changeMembers(
                    group.id(), group.version(), List.of(new Reference(user, 0L)), true));
    failure(
        "GROUP_VERSION_CONFLICT",
        () ->
            platform(
                () ->
                    service.changeMembers(
                        group.id(), group.version(), List.of(new Reference(user, 1L)), false)));
    platform(
        () ->
            service.changeMembers(
                group.id(), added.version(), List.of(new Reference(user, 1L)), false));
    assertThat(get(group.id()).memberCount()).isZero();
  }

  @Test
  void concurrentTreeWritesHaveOneWinnerAndOneVersionConflict() throws Exception {
    var group = create("team", null);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<String> operation =
          () -> {
            barrier.await();
            try {
              platform(
                  () ->
                      service.update(
                          group.id(),
                          group.version(),
                          group.organizationVersion(),
                          "changed",
                          "",
                          0,
                          "ACTIVE"));
              return "OK";
            } catch (PlatformException e) {
              return e.code();
            }
          };
      var first = executor.submit(operation);
      var second = executor.submit(operation);
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "ORGANIZATION_VERSION_CONFLICT");
    }
  }

  @Test
  void manualPermissionRequiresRegisteredServiceAndPreservesSourceAndSemantics() {
    var permissionService =
        new com.ken.platform.identity.service.permission.ManualPermissionService(
            catalog,
            new com.ken.platform.identity.dal.mapper.permission.PermissionManagementRepository(
                jdbc),
            accounts,
            auditRows,
            new PlatformManagementAuthorization(accounts),
            scope,
            new ManagementAudits(new SecurityAuditRepository(jdbc), manager),
            manager);
    String code = "fixture:" + UUID.randomUUID();
    String serviceName = "fixture-" + UUID.randomUUID();
    var definition =
        new com.ken.platform.identity.service.permission.PermissionCatalogService.Entry(
            code,
            "查看订单",
            com.ken.platform.identity.service.permission.PermissionCatalogService.PrincipalType
                .TENANT,
            "orders",
            "订单管理",
            "order",
            "订单",
            "read",
            "测试权限",
            0,
            Set.of(com.ken.platform.foundation.security.DataScope.SELF),
            "orders.read",
            com.ken.platform.identity.service.permission.PermissionCatalogService.Status.ACTIVE);
    long version =
        platform(() -> permissionService.list(null, null, null, null, "", 0, 20).catalogVersion());
    failure(
        "INVALID_ARGUMENT",
        () -> platform(() -> permissionService.create(serviceName, definition, version)));
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES(?,?)",
        serviceName,
        serviceName);
    var saved = platform(() -> permissionService.create(serviceName, definition, version));
    assertThat(saved.permission().source()).isEqualTo("MANUAL");
    var details = platform(() -> permissionService.get(code));
    assertThat(details.roleCount()).isZero();
    assertThat(details.userCount()).isZero();
    failure(
        "CATALOG_VERSION_CONFLICT",
        () ->
            platform(
                () ->
                    permissionService.update(
                        code, version, "changed", "orders", "订单管理", "订单", "", 0, "ACTIVE")));
    failure(
        "PERMISSION_SOURCE_PROTECTED",
        () ->
            platform(
                () ->
                    permissionService.update(
                        "identity:user:read",
                        saved.catalogVersion(),
                        "changed",
                        "identity",
                        "身份",
                        "用户",
                        "",
                        0,
                        "DISABLED")));
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'SELF')",
                tenant,
                role,
                code));
    data(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                user,
                role));
    var used = platform(() -> permissionService.get(code));
    assertThat(used.roleCount()).isEqualTo(1);
    assertThat(used.userCount()).isEqualTo(1);
    var disabled =
        platform(
            () ->
                permissionService.update(
                    code,
                    saved.catalogVersion(),
                    "查看订单",
                    "orders",
                    "订单管理",
                    "订单",
                    "",
                    0,
                    "DISABLED"));
    assertThat(disabled.permission().code()).isEqualTo(code);
    assertThat(disabled.permission().oauthScope()).isEqualTo("orders.read");
    var auth =
        new com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository(grants);
    var permissions =
        data(
            tenant,
            () ->
                new com.ken.platform.identity.service.authorization.AuthorizationEvaluator(clock)
                    .evaluate(auth.permissions(), auth.grants(tenant, user), null)
                    .permissions());
    assertThat(permissions).doesNotContain(code);
  }
}
