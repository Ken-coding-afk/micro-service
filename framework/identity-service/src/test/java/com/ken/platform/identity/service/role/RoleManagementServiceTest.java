package com.ken.platform.identity.service.role;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.role.RoleManagementMapper;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class RoleManagementServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private RoleManagementService service;
  private RoleManagementMapper roleSql;
  private UUID tenant;
  private final UUID actor = UUID.randomUUID();
  private final UUID adminRole = UUID.randomUUID();
  private final UUID source = UUID.randomUUID();
  private final List<UUID> created = new ArrayList<>();

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
    configuration.setMaximumPoolSize(2);
    pool = new HikariDataSource(configuration);
    jdbc = new JdbcTemplate(pool);
    transactions = new TenantTransactions(jdbc, new JdbcTransactionManager(pool));
    var mybatis = new Configuration();
    mybatis.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    mybatis.addMapper(UserManagementMapper.class);
    mybatis.addMapper(RolePermissionMapper.class);
    mybatis.addMapper(RoleManagementMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    var template = new SqlSessionTemplate(factory.getObject());
    var users = template.getMapper(UserManagementMapper.class);
    roleSql = spy(template.getMapper(RoleManagementMapper.class));
    service =
        new RoleManagementService(
            transactions,
            users,
            template.getMapper(RolePermissionMapper.class),
            roleSql,
            new TenantManagementAuthorization(users),
            Clock.systemUTC(),
            audits());
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('identity:role:manage','管理角色','identity-service','TENANT','identity','身份','role','角色','manage',ARRAY['TENANT'],'identity.roles') ON CONFLICT DO NOTHING");
          jdbc.update(
              "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('test:copy','复制测试','document-service','TENANT','test','测试','test-copy','复制测试','read',ARRAY['SELF','TENANT'],'documents.copy') ON CONFLICT (code) DO UPDATE SET status='ACTIVE'");
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
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'identity:role:manage','TENANT')",
              tenant,
              adminRole);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
              tenant,
              actor,
              adminRole);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,status) VALUES (?,?,?,'源角色','DISABLED')",
              source,
              tenant,
              "source-" + source);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,'test:copy','SELF')",
              tenant,
              source);
          return null;
        });
    created.addAll(List.of(adminRole, source));
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
            for (UUID role : created) {
              jdbc.update(
                  "DELETE FROM sys_user_role WHERE tenant_id=? AND role_id=?", tenant, role);
              jdbc.update(
                  "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?", tenant, role);
              jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, role);
            }
            jdbc.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", tenant, actor);
            jdbc.update("UPDATE sys_permission SET status='ACTIVE' WHERE code='test:copy'");
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

  private RoleManagementService.View remember(RoleManagementService.View role) {
    created.add(role.id());
    return role;
  }

  private long catalogVersion() {
    return jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class);
  }

  @Test
  void descriptionChangesOnlyMetadataVersionAndIdenticalNormalizedValueIsNoOp() {
    var role =
        remember(authorized(() -> service.create("desc-" + UUID.randomUUID(), "说明角色", "旧说明")));
    var changed =
        authorized(
            () ->
                service.update(
                    role.id(),
                    0,
                    role.name(),
                    RoleManagementService.Status.ACTIVE,
                    " 新说明\r\n第二行 "));
    assertThat(changed.description()).isEqualTo("新说明\n第二行");
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.authorizationVersion()).isZero();
    assertThat(
            authorized(
                    () ->
                        service.update(
                            role.id(),
                            1,
                            role.name(),
                            RoleManagementService.Status.ACTIVE,
                            "新说明\n第二行"))
                .version())
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                authorized(
                    () ->
                        service.update(
                            role.id(),
                            0,
                            role.name(),
                            RoleManagementService.Status.ACTIVE,
                            "过期草稿")))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT description FROM sys_role WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        role.id())))
        .isEqualTo("新说明\n第二行");
  }

  @Test
  void oldSignaturesPreserveDescriptionAndCopyInheritsUnlessExplicitlyOverridden() {
    var role =
        remember(authorized(() -> service.create("inherit-" + UUID.randomUUID(), "源说明", "保留说明")));
    var updated =
        authorized(
            () -> service.update(role.id(), 0, "旧调用更新名称", RoleManagementService.Status.ACTIVE));
    assertThat(updated.description()).isEqualTo("保留说明");
    var copied =
        remember(
            authorized(
                () ->
                    service.copy(
                        role.id(), 1, catalogVersion(), "copy-old-" + UUID.randomUUID(), "旧调用复制")));
    assertThat(copied.description()).isEqualTo("保留说明");
    var overridden =
        remember(
            authorized(
                () ->
                    service.copy(
                        role.id(),
                        1,
                        catalogVersion(),
                        "copy-new-" + UUID.randomUUID(),
                        "指定说明",
                        "新的副本说明")));
    assertThat(overridden.description()).isEqualTo("新的副本说明");
    var empty = remember(authorized(() -> service.create("empty-" + UUID.randomUUID(), "旧创建")));
    assertThat(empty.description()).isEmpty();
    assertThat(
            authorized(
                    () ->
                        service.update(
                            role.id(), 1, updated.name(), RoleManagementService.Status.ACTIVE, ""))
                .description())
        .isEmpty();
  }

  @Test
  void descriptionLimitUsesUnicodeCodepointsAndPostgresPersistsFullBoundary() {
    String description = new String(Character.toChars(0x1D800)) + "😀".repeat(1023);
    var role =
        remember(
            authorized(
                () ->
                    service.create("unicode-desc-" + UUID.randomUUID(), "Unicode说明", description)));
    assertThat(role.description()).isEqualTo(description);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT char_length(description) FROM sys_role WHERE tenant_id=? AND id=?",
                        Integer.class,
                        tenant,
                        role.id())))
        .isEqualTo(1024);
  }

  static java.util.stream.Stream<String> invalidDescriptions() {
    return java.util.stream.Stream.of(
        "<b>说明</b>",
        "bad" + (char) 0,
        "bad\t",
        "bad\r",
        "bad" + (char) 0x85,
        "😀".repeat(1025),
        "bad" + (char) 0xD800,
        "bad" + (char) 0xDC00);
  }

  @ParameterizedTest
  @MethodSource("invalidDescriptions")
  void invalidDescriptionFailsBeforeMutationAndDoesNotEnterFailureAudit(String description) {
    String code = "invalid-desc-" + UUID.randomUUID();
    assertThatThrownBy(() -> authorized(() -> service.create(code, "说明测试", description)))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error ->
                assertThat(((PlatformException) error).code())
                    .isEqualTo("ROLE_DESCRIPTION_INVALID"));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role WHERE tenant_id=? AND code=?",
                        Integer.class,
                        tenant,
                        code)))
        .isZero();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT details::text FROM sys_auth_audit WHERE tenant_id=? AND actor_id=? AND action='identity.role.created' AND result='FAILURE' ORDER BY created_at DESC LIMIT 1",
                        String.class,
                        tenant,
                        actor)))
        .contains("ROLE_DESCRIPTION_INVALID")
        .doesNotContain("description", "说明测试");
  }

  @Test
  void descriptionAndVersionRollBackWhenSuccessAuditFails() {
    var role =
        remember(
            authorized(() -> service.create("rollback-desc-" + UUID.randomUUID(), "回滚说明", "原说明")));
    doThrow(new IllegalStateException("audit fixture"))
        .when(roleSql)
        .audit(
            any(UUID.class),
            eq(tenant),
            eq("identity.role.updated"),
            eq(role.id().toString()),
            anyString(),
            anyString());
    assertThatThrownBy(
            () ->
                authorized(
                    () ->
                        service.update(
                            role.id(),
                            0,
                            role.name(),
                            RoleManagementService.Status.ACTIVE,
                            "不得提交的说明")))
        .isInstanceOf(IllegalStateException.class);
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT description FROM sys_role WHERE tenant_id=? AND id=?",
                      String.class,
                      tenant,
                      role.id()))
              .isEqualTo("原说明");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT version FROM sys_role WHERE tenant_id=? AND id=?",
                      Long.class,
                      tenant,
                      role.id()))
              .isZero();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT authorization_version FROM sys_role WHERE tenant_id=? AND id=?",
                      Long.class,
                      tenant,
                      role.id()))
              .isZero();
          return null;
        });
  }

  @Test
  void deleteEmptyRoleRemovesOnlyItsPermissionRelationsAndKeepsAudit() {
    var deleted = authorized(() -> service.delete(source, 0));
    assertThat(deleted.id()).isEqualTo(source);
    assertThat(deleted.deleted()).isTrue();
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_role WHERE tenant_id=? AND id=?",
                      Integer.class,
                      tenant,
                      source))
              .isZero();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                      Integer.class,
                      tenant,
                      source))
              .isZero();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_role WHERE tenant_id=? AND id=?",
                      Integer.class,
                      tenant,
                      adminRole))
              .isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_auth_audit WHERE tenant_id=? AND target_id=? AND action='identity.role.deleted' AND result='SUCCESS'",
                      Integer.class,
                      tenant,
                      source.toString()))
              .isEqualTo(1);
          return null;
        });
    assertThatThrownBy(() -> authorized(() -> service.delete(source, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(404));
  }

  @Test
  void deleteRejectsProtectedNonemptyAndStaleRolesWithoutMutation() {
    assertThatThrownBy(() -> authorized(() -> service.delete(adminRole, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(403));
    assertThatThrownBy(() -> authorized(() -> service.delete(source, 99)))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error ->
                assertThat(((PlatformException) error).code()).isEqualTo("ROLE_VERSION_CONFLICT"));
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                actor,
                source));
    assertThatThrownBy(() -> authorized(() -> service.delete(source, 0)))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error -> assertThat(((PlatformException) error).code()).isEqualTo("ROLE_NOT_EMPTY"));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                        Integer.class,
                        tenant,
                        source)))
        .isEqualTo(1);
  }

  @Test
  void deleteAuditFailureRollsBackRoleAndAllPermissionRelations() {
    doThrow(new IllegalStateException("delete audit fixture"))
        .when(roleSql)
        .audit(
            any(UUID.class),
            eq(tenant),
            eq("identity.role.deleted"),
            eq(source.toString()),
            anyString(),
            anyString());
    assertThatThrownBy(() -> authorized(() -> service.delete(source, 0)))
        .isInstanceOf(IllegalStateException.class);
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT version FROM sys_role WHERE tenant_id=? AND id=?",
                      Long.class,
                      tenant,
                      source))
              .isZero();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT data_scope FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_code='test:copy'",
                      String.class,
                      tenant,
                      source))
              .isEqualTo("SELF");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_auth_audit WHERE tenant_id=? AND target_id=? AND action='identity.role.deleted' AND result='SUCCESS'",
                      Integer.class,
                      tenant,
                      source.toString()))
              .isZero();
          return null;
        });
  }

  @Test
  void nameOnlyChangeKeepsAuthorizationVersionButStatusChangesAdvanceIt() {
    var role = remember(authorized(() -> service.create("new-" + UUID.randomUUID(), "新角色")));
    assertThat(role.protectedRole()).isFalse();
    var renamed =
        authorized(() -> service.update(role.id(), 0, "新名称", RoleManagementService.Status.ACTIVE));
    assertThat(renamed.version()).isEqualTo(1);
    assertThat(renamed.authorizationVersion()).isZero();
    var disabled =
        authorized(
            () -> service.update(role.id(), 1, "新名称", RoleManagementService.Status.DISABLED));
    assertThat(disabled.authorizationVersion()).isEqualTo(1);
    var enabled =
        authorized(() -> service.update(role.id(), 2, "新名称", RoleManagementService.Status.ACTIVE));
    assertThat(enabled.authorizationVersion()).isEqualTo(2);
    assertThat(
            authorized(
                    () -> service.update(role.id(), 3, "新名称", RoleManagementService.Status.ACTIVE))
                .version())
        .isEqualTo(3);
  }

  @Test
  void copyingDisabledRolePreservesStateAndExplicitPermissionScope() {
    var copy =
        remember(
            authorized(
                () ->
                    service.copy(source, 0, catalogVersion(), "copy-" + UUID.randomUUID(), "复本")));
    assertThat(copy.status()).isEqualTo(RoleManagementService.Status.DISABLED);
    assertThat(copy.protectedRole()).isFalse();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT data_scope FROM sys_role_permission WHERE role_id=? AND permission_code='test:copy'",
                        String.class,
                        copy.id())))
        .isEqualTo("SELF");
  }

  @Test
  void staleSourceCatalogAndProtectedRoleCannotBeCopiedOrEdited() {
    assertThatThrownBy(
            () -> authorized(() -> service.copy(source, 99, catalogVersion(), "copy-a", "复本")))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThatThrownBy(
            () -> authorized(() -> service.copy(source, 0, catalogVersion() + 1, "copy-b", "复本")))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(409));
    assertThatThrownBy(
            () ->
                authorized(() -> service.copy(adminRole, 0, catalogVersion(), "copy-admin", "复本")))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(403));
    assertThatThrownBy(
            () ->
                authorized(
                    () ->
                        service.update(adminRole, 0, "伪造", RoleManagementService.Status.DISABLED)))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(403));
  }

  @Test
  void copyRejectsWholeSourceWhenHistoricalPermissionCannotBeGrantedAgain() {
    jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE code='test:copy'");
    String code = "rejected-" + UUID.randomUUID();
    assertThatThrownBy(
            () -> authorized(() -> service.copy(source, 0, catalogVersion(), code, "复本")))
        .isInstanceOf(PlatformException.class)
        .satisfies(error -> assertThat(((PlatformException) error).status()).isEqualTo(400));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role WHERE code=?", Integer.class, code)))
        .isZero();
  }

  @Test
  void duplicateCodesConflictAndUnicodeLengthUsesCodepoints() {
    String code = "unique-" + UUID.randomUUID();
    remember(authorized(() -> service.create(code, "😀".repeat(128))));
    assertThatThrownBy(() -> authorized(() -> service.create(code, "重复")))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error ->
                assertThat(((PlatformException) error).code()).isEqualTo("ROLE_CODE_CONFLICT"));
    assertThatThrownBy(() -> authorized(() -> service.create("invalid;sql", "名称")))
        .isInstanceOf(PlatformException.class);
    assertThatThrownBy(() -> authorized(() -> service.create("valid-name", "😀".repeat(129))))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void everyRoleLifecycleWriteRecordsFailureWithoutPartialMutation() {
    assertThatThrownBy(() -> authorized(() -> service.create("source-" + source, "敏感输入不进入失败审计")))
        .isInstanceOf(PlatformException.class);
    assertThatThrownBy(
            () ->
                authorized(
                    () ->
                        service.update(
                            source, 999, "敏感输入不进入失败审计", RoleManagementService.Status.ACTIVE)))
        .isInstanceOf(PlatformException.class);
    assertThatThrownBy(
            () ->
                authorized(
                    () ->
                        service.copy(source, 999, catalogVersion(), "failed-copy", "敏感输入不进入失败审计")))
        .isInstanceOf(PlatformException.class);
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM sys_auth_audit WHERE actor_id=? AND result='FAILURE' ORDER BY action",
                String.class,
                actor))
        .containsExactly("identity.role.copied", "identity.role.created", "identity.role.updated");
    assertThat(
            jdbc.queryForList(
                "SELECT details::text FROM sys_auth_audit WHERE actor_id=? AND result='FAILURE'",
                String.class,
                actor))
        .allSatisfy(value -> assertThat(value).doesNotContain("敏感输入"));
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT version FROM sys_role WHERE id=?", Long.class, source)))
        .isZero();
  }
}
