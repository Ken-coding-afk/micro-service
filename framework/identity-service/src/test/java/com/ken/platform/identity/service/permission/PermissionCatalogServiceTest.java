package com.ken.platform.identity.service.permission;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.permission.PermissionCatalogService.Entry;
import com.ken.platform.identity.service.permission.PermissionCatalogService.PrincipalType;
import com.ken.platform.identity.service.permission.PermissionCatalogService.Status;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class PermissionCatalogServiceTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private PermissionCatalogService service;
  private PermissionCatalogMapper mapper;
  private final String owner = "catalog-" + UUID.randomUUID();
  private final List<UUID> tenants = new ArrayList<>();
  private final UUID role = UUID.randomUUID();

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
    tenants.add(UUID.fromString(binding.get("tenant_a").asString()));
    tenants.add(UUID.fromString(binding.get("tenant_b").asString()));
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
    config.setMaximumPoolSize(3);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    var manager = new JdbcTransactionManager(pool);
    transactions = new TenantTransactions(jdbc, manager);
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    configuration.addMapper(PermissionCatalogMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(configuration);
    mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PermissionCatalogMapper.class);
    service = new PermissionCatalogService(mapper, manager, audits());
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (pool == null) {
      return;
    }
    try {
      for (UUID tenant : tenants) {
        tenant(
            tenant,
            () -> {
              jdbc.update(
                  "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                  tenant,
                  role(tenant));
              jdbc.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", tenant, role(tenant));
              return null;
            });
      }
      jdbc.update("DELETE FROM sys_permission WHERE owner_service=?", owner);
    } finally {
      pool.close();
    }
  }

  private UUID role(UUID tenant) {
    return UUID.nameUUIDFromBytes(
        (tenant.toString() + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private long version() {
    return jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class);
  }

  private Entry entry(String action, Status status) {
    return new Entry(
        owner + ":" + action,
        "文档" + action,
        PrincipalType.TENANT,
        "documents",
        "文档",
        "document",
        "文档",
        action,
        "纯文本",
        10,
        Set.of(DataScope.SELF, DataScope.TENANT),
        "documents.read",
        status);
  }

  private Entry scope(Entry entry, Set<DataScope> scopes) {
    return new Entry(
        entry.code(),
        entry.name(),
        entry.principalType(),
        entry.groupCode(),
        entry.groupName(),
        entry.resourceCode(),
        entry.resourceName(),
        entry.actionCode(),
        entry.description(),
        entry.sort(),
        scopes,
        entry.oauthScope(),
        entry.status());
  }

  private <T> T authorized(String allowedOwner, String type, UUID tenant, Supplier<T> action) {
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_catalog.publish:" + allowedOwner));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            "catalog-publisher",
            tenant == null
                ? Map.of("principal_type", type)
                : Map.of("principal_type", type, "tenant_id", tenant.toString()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext(
                "catalog-" + UUID.randomUUID(), tenant, principal.getName(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private PermissionCatalogService.Result publish(long version, Entry... entries) {
    return authorized(
        owner, "service", null, () -> service.publish(owner, "1.2.3", version, List.of(entries)));
  }

  private <T> T tenant(UUID tenant, Supplier<T> action) {
    try (var ignored =
        CallContexts.bind(new CallContext("catalog-data", tenant, "fixture", null, null, null))) {
      return transactions.execute(action);
    }
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
  void publishesIdempotentlyPreservesOmissionsAndDoesNotGrantNewPermissions() {
    long before = version();
    var first = publish(before, entry("read", Status.ACTIVE));
    assertThat(first.catalogVersion()).isEqualTo(before + 1);
    assertThat(first.changed()).isTrue();
    var again = publish(first.catalogVersion(), entry("read", Status.ACTIVE));
    assertThat(again.changed()).isFalse();
    assertThat(again.digest()).isEqualTo(first.digest());
    for (UUID tenant : tenants) {
      tenant(
          tenant,
          () -> {
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,?,'目录角色')",
                role(tenant),
                tenant,
                owner);
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,?,'SELF')",
                tenant,
                role(tenant),
                entry("read", Status.ACTIVE).code());
            return null;
          });
    }
    var added = publish(again.catalogVersion(), entry("write", Status.ACTIVE));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isEqualTo(2);
    for (UUID tenant : tenants) {
      assertThat(
              tenant(
                  tenant,
                  () ->
                      jdbc.queryForList(
                          "SELECT permission_code FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                          String.class,
                          tenant,
                          role(tenant))))
          .containsExactly(entry("read", Status.ACTIVE).code());
    }
    var audit =
        jdbc.queryForObject(
            "SELECT details::text FROM sys_auth_audit WHERE action='identity.permissions.published' AND target_id=? ORDER BY created_at DESC LIMIT 1",
            String.class,
            owner);
    assertThat(audit).contains("1.2.3", added.digest(), "catalog-publisher");
    assertThat(publish(added.catalogVersion()).changed()).isFalse();
  }

  @Test
  void publisherMustBeAuthorizedProjectServiceAndReservedTypesAreRejected() {
    long before = version();
    failure(
        "CATALOG_PUBLISH_FORBIDDEN",
        403,
        () ->
            authorized(
                "other-service",
                "service",
                null,
                () -> service.publish(owner, "1", before, List.of(entry("read", Status.ACTIVE)))));
    for (String type : List.of("user", "platform")) {
      failure(
          "INVALID_CREDENTIALS",
          401,
          () ->
              authorized(
                  owner,
                  type,
                  null,
                  () ->
                      service.publish(owner, "1", before, List.of(entry("read", Status.ACTIVE)))));
    }
    failure(
        "INVALID_CREDENTIALS",
        401,
        () ->
            authorized(
                owner,
                "service",
                tenants.getFirst(),
                () -> service.publish(owner, "1", before, List.of(entry("read", Status.ACTIVE)))));
    var original = entry("read", Status.ACTIVE);
    for (String code : List.of("identity:user:manage", "platform:tenant:manage")) {
      var reserved =
          new Entry(
              code,
              original.name(),
              PrincipalType.TENANT,
              original.groupCode(),
              original.groupName(),
              original.resourceCode(),
              original.resourceName(),
              original.actionCode(),
              "",
              10,
              Set.of(),
              original.oauthScope(),
              original.status());
      failure("PERMISSION_CATALOG_INVALID", 400, () -> publish(before, reserved));
    }
    var platform =
        new Entry(
            original.code(),
            original.name(),
            PrincipalType.PLATFORM,
            original.groupCode(),
            original.groupName(),
            original.resourceCode(),
            original.resourceName(),
            original.actionCode(),
            "",
            10,
            Set.of(),
            original.oauthScope(),
            original.status());
    failure("PERMISSION_CATALOG_INVALID", 400, () -> publish(before, platform));
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void ownershipAndSemanticChangesAreRejectedWithoutPartialWrites() {
    var initial = publish(version(), entry("read", Status.ACTIVE));
    failure(
        "PERMISSION_SEMANTICS_CONFLICT",
        409,
        () ->
            publish(
                initial.catalogVersion(),
                entry("write", Status.ACTIVE),
                scope(entry("read", Status.ACTIVE), Set.of(DataScope.SELF))));
    failure(
        "PERMISSION_OWNER_CONFLICT",
        409,
        () ->
            authorized(
                "another-service",
                "service",
                null,
                () ->
                    service.publish(
                        "another-service",
                        "1",
                        initial.catalogVersion(),
                        List.of(entry("read", Status.ACTIVE)))));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isEqualTo(1);
    assertThat(version()).isEqualTo(initial.catalogVersion());
  }

  @Test
  void disableRequiresDeprecatedAndDoesNotDeleteHistoricalGrants() {
    long current = publish(version(), entry("read", Status.ACTIVE)).catalogVersion();
    UUID tenant = tenants.getFirst();
    tenant(
        tenant,
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,?,'历史角色')",
              role(tenant),
              tenant,
              owner);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES (?,?,?,'TENANT')",
              tenant,
              role(tenant),
              entry("read", Status.ACTIVE).code());
          return null;
        });
    failure(
        "PERMISSION_CATALOG_INVALID", 400, () -> publish(current, entry("read", Status.DISABLED)));
    long deprecated = publish(current, entry("read", Status.DEPRECATED)).catalogVersion();
    long disabled = publish(deprecated, entry("read", Status.DISABLED)).catalogVersion();
    assertThat(disabled).isEqualTo(current + 2);
    assertThat(
            tenant(
                tenant,
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                        Integer.class,
                        tenant,
                        role(tenant))))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_permission WHERE code=?",
                String.class,
                entry("read", Status.ACTIVE).code()))
        .isEqualTo("DISABLED");
  }

  @Test
  void metadataMustBeCoherentAndEmptyScopeHasNoTenantMeaning() {
    var read = entry("read", Status.ACTIVE);
    var write = entry("write", Status.ACTIVE);
    var conflictingGroup =
        new Entry(
            write.code(),
            write.name(),
            write.principalType(),
            write.groupCode(),
            "另一个组名",
            write.resourceCode(),
            write.resourceName(),
            write.actionCode(),
            "",
            10,
            Set.of(),
            write.oauthScope(),
            write.status());
    long before = version();
    failure("PERMISSION_CATALOG_INVALID", 400, () -> publish(before, read, conflictingGroup));
    failure("PERMISSION_CATALOG_INVALID", 400, () -> publish(before, read, read));
    var defaultGroup =
        new Entry(
            read.code(),
            read.name(),
            read.principalType(),
            null,
            null,
            read.resourceCode(),
            read.resourceName(),
            read.actionCode(),
            null,
            0,
            Set.of(),
            read.oauthScope(),
            read.status());
    publish(before, defaultGroup);
    assertThat(
            jdbc.queryForObject(
                "SELECT group_code FROM sys_permission WHERE code=?", String.class, read.code()))
        .isEqualTo(owner);
    assertThat(
            jdbc.queryForObject(
                "SELECT cardinality(supported_data_scopes) FROM sys_permission WHERE code=?",
                Integer.class,
                read.code()))
        .isZero();
  }

  @Test
  void verifiedArtifactRecordsRawChecksumAndRelease() throws Exception {
    String yaml =
        """
        service: %s
        permissions:
          - code: %s:read
            name: 查看文档
            resource_code: document
            resource_name: 文档
            action_code: read
            data_scopes: [SELF, TENANT]
            oauth_scope: documents.read
        """
            .formatted(owner, owner);
    String checksum =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var manifest = PermissionManifest.parse(owner, "3.2.1", checksum, yaml);
    authorized(owner, "service", null, () -> service.publish(manifest, version()));
    var audit =
        jdbc.queryForObject(
            "SELECT details::text FROM sys_auth_audit WHERE action='identity.permissions.published' AND target_id=?",
            String.class,
            owner);
    assertThat(audit).contains("artifact_checksum", checksum, "3.2.1");
  }

  @Test
  void scopeArrayOrderDoesNotCreateFalseChanges() {
    long current = publish(version(), entry("read", Status.ACTIVE)).catalogVersion();
    jdbc.update(
        "UPDATE sys_permission SET supported_data_scopes=ARRAY['TENANT','SELF'] WHERE code=?",
        entry("read", Status.ACTIVE).code());
    assertThat(publish(current, entry("read", Status.ACTIVE)).changed()).isFalse();
    assertThat(version()).isEqualTo(current);
  }

  @Test
  void auditFailureRollsBackPermissionRowsAndCatalogVersion() {
    long before = version();
    var failing = org.mockito.Mockito.spy(mapper);
    org.mockito.Mockito.doThrow(new IllegalStateException("fixture-audit-failure"))
        .when(failing)
        .audit(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    service = new PermissionCatalogService(failing, new JdbcTransactionManager(pool), audits());
    assertThatThrownBy(() -> publish(before, entry("read", Status.ACTIVE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fixture-audit-failure");
    assertThat(version()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isZero();
  }

  @Test
  void unchangedReleaseIsAuditedWithoutAdvancingCatalogVersion() {
    long current = publish(version(), entry("read", Status.ACTIVE)).catalogVersion();
    var result =
        authorized(
            owner,
            "service",
            null,
            () -> service.publish(owner, "1.2.4", current, List.of(entry("read", Status.ACTIVE))));
    assertThat(result.changed()).isFalse();
    assertThat(result.catalogVersion()).isEqualTo(current);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE target_id=? AND details->>'release_version'='1.2.4' AND jsonb_array_length(details->'changed_codes')=0",
                Integer.class,
                owner))
        .isEqualTo(1);
  }

  @Test
  void concurrentSameVersionPublishHasExactlyOneWinner() throws Exception {
    long before = version();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          List.of("read", "write").stream()
              .map(
                  action ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                            try {
                              publish(before, entry(action, Status.ACTIVE));
                              return "OK";
                            } catch (PlatformException error) {
                              return error.code();
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  tasks.getFirst().get(10, TimeUnit.SECONDS),
                  tasks.getLast().get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "CATALOG_VERSION_CONFLICT");
    }
    assertThat(version()).isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isEqualTo(1);
  }

  @Test
  void publicManifestFailureKeepsServiceIdentityButNoManifestOrToken() throws Exception {
    String request = "failed-publish-" + UUID.randomUUID();
    String yaml =
        """
        service: %s
        permissions:
          - code: %s:read
            name: 查看文档
            resource_code: document
            resource_name: 文档
            action_code: read
            data_scopes: [SELF, TENANT]
            oauth_scope: documents.read
            description: fixture-private-manifest
        """
            .formatted(owner, owner);
    String checksum =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var manifest = PermissionManifest.parse(owner, "4.0.0", checksum, yaml);
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_catalog.publish:" + owner));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            owner,
            Map.of("principal_type", "service", "access_token", "fixture-private-token"),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext(request, null, owner, "fixture-private-token", null, null))) {
      failure("CATALOG_VERSION_CONFLICT", 409, () -> service.publish(manifest, version() + 1));
    }
    var event =
        jdbc.queryForMap(
            "SELECT actor_type,tenant_id,result,details::text AS details FROM sys_auth_audit WHERE request_id=?",
            request);
    assertThat(event.get("actor_type")).isEqualTo("SERVICE");
    assertThat(event.get("tenant_id")).isNull();
    assertThat(event.get("result")).isEqualTo("FAILURE");
    assertThat((String) event.get("details"))
        .contains(owner, "CATALOG_VERSION_CONFLICT")
        .doesNotContain("fixture-private", "access_token");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isZero();
  }

  @Test
  void publisherRevokedBeforeWriteLeavesCatalogUnchanged() throws Exception {
    String yaml =
        """
        service: %s
        permissions:
          - code: %s:read
            name: 读取
            principal_type: TENANT
            group_code: samples
            group_name: 示例
            resource_code: sample
            resource_name: 示例
            action_code: read
            data_scopes: [SELF, TENANT]
            oauth_scope: samples.read
        """
            .formatted(owner, owner);
    String checksum =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var manifest = PermissionManifest.parse(owner, "1.0.0", checksum, yaml);
    long before = version();
    authorized(
        owner,
        "service",
        null,
        () -> {
          failure(
              "INVALID_CREDENTIALS",
              401,
              () ->
                  service.publish(
                      manifest,
                      before,
                      () -> {
                        assertThat(
                                org.springframework.transaction.support
                                    .TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                        throw new PlatformException("INVALID_CREDENTIALS", "发布身份已失效", 401);
                      }));
          return null;
        });
    assertThat(version()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE owner_service=?", Integer.class, owner))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.permissions.published' AND target_id=? AND result='SUCCESS'",
                Integer.class,
                owner))
        .isZero();
  }
}
