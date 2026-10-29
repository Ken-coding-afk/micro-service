package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitoringScopeUpgradeTest {
  private ClientPolicyRules.Definition value(Set<String> scopes, String callback) {
    return new ClientPolicyRules.Definition(
        "identity-admin-platform",
        "平台身份管理",
        PrincipalType.PLATFORM,
        null,
        Set.of(),
        Set.of("identity-service"),
        scopes,
        Set.of(callback),
        Set.of("https://app.example/logout"),
        false,
        true,
        Status.ACTIVE);
  }

  @Test
  void migrationAllowsOnlyOneExplicitScopeAddition() {
    var before = value(Set.of("profile", "platform.audit"), "https://app.example/callback");
    var after =
        value(
            Set.of("profile", "platform.audit", "platform.monitoring"),
            "https://app.example/callback");
    assertThat(ClientManagementService.monitoringScopeUpgrade(before, after)).isTrue();
    assertThat(ClientManagementService.monitoringScopeUpgrade(after, after)).isFalse();
    assertThat(
            ClientManagementService.monitoringScopeUpgrade(
                before, value(after.scopes(), "https://other.example/callback")))
        .isFalse();
    assertThat(
            ClientManagementService.monitoringScopeUpgrade(
                before, value(Set.of("platform.monitoring"), "https://app.example/callback")))
        .isFalse();
  }

  @Test
  void separatelyRegisteredPlatformClientsMayUseManagementScopes() {
    var v =
        value(Set.of("platform.identity", "platform.permissions"), "https://app.example/callback");
    var client =
        new ClientPolicyRules.Definition(
            "project-admin-client",
            v.name(),
            v.principalType(),
            v.ownerTenantId(),
            v.allowedTenantIds(),
            v.audiences(),
            v.scopes(),
            v.redirectUris(),
            v.logoutUris(),
            v.publicClient(),
            v.firstParty(),
            v.status());
    assertThat(new ClientPolicyRules("local").validate(client).clientId())
        .isEqualTo("project-admin-client");
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "PLATFORM_TEST_MAINTENANCE_DATABASE_BINDING",
      matches = ".+")
  void bootstrapRejectsChangedIdentityScopesInsteadOfMigratingOldClient() throws Exception {
    var binding =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of(
                        System.getenv("PLATFORM_TEST_MAINTENANCE_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    var config = new com.zaxxer.hikari.HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + binding.get("host").asString()
            + ":"
            + binding.get("port").asInt()
            + "/"
            + binding.get("dbname").asString());
    config.setUsername(binding.get("username").asString());
    config.setPassword(
        java.nio.file.Files.readString(
                java.nio.file.Path.of(binding.get("password_file").asString()))
            .strip());
    try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(pool);
      var manager = new org.springframework.jdbc.support.JdbcTransactionManager(pool);
      var configuration = new org.apache.ibatis.session.Configuration();
      configuration
          .getTypeHandlerRegistry()
          .register(java.util.UUID.class, com.ken.platform.foundation.data.UuidTypeHandler.class);
      configuration.addMapper(
          com.ken.platform.identity.dal.mapper.account.AccountManagementMapper.class);
      configuration.addMapper(
          com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper.class);
      var factory = new org.mybatis.spring.SqlSessionFactoryBean();
      factory.setDataSource(pool);
      factory.setConfiguration(configuration);
      var sql = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
      var accounts =
          sql.getMapper(com.ken.platform.identity.dal.mapper.account.AccountManagementMapper.class);
      var credentials =
          new com.ken.platform.identity.service.security.PasswordCredentials(
              sql.getMapper(
                  com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper.class));
      var store = new com.ken.platform.identity.dal.mapper.oauth.ClientStore(jdbc);
      var service =
          new ClientManagementService(
              manager,
              store,
              new ClientPolicyRules("local"),
              null,
              accounts,
              null,
              null,
              credentials,
              java.time.Clock.systemUTC(),
              null);
      new org.springframework.transaction.support.TransactionTemplate(manager)
          .execute(
              status -> {
                assertThat(store.bootstrapClient("PLATFORM_BFF")).isNull();
                assertThat(store.byClientId("identity-admin-platform")).isNull();
                String secret = "scope-upgrade-fixture-abcdefghijklmnopqrstuvwxyz";
                String hash = credentials.encoder().encode(secret);
                var before =
                    value(Set.of("profile", "platform.audit"), "https://app.example/callback");
                var after =
                    value(
                        Set.of("profile", "platform.audit", "platform.identity"),
                        "https://app.example/callback");
                var original = service.initialize("PLATFORM_BFF", before, secret, hash);
                assertThat(service.initialize("PLATFORM_BFF", before, secret, hash))
                    .isEqualTo(original);
                assertThatThrownBy(() -> service.initialize("PLATFORM_BFF", after, secret, hash))
                    .isInstanceOf(com.ken.platform.foundation.http.PlatformException.class)
                    .extracting(
                        error ->
                            ((com.ken.platform.foundation.http.PlatformException) error).code())
                    .isEqualTo("IDENTITY_BOOTSTRAP_CONFLICT");
                assertThat(store.byId(original.id().toString()).policy().version())
                    .isEqualTo(original.version());
                status.setRollbackOnly();
                return null;
              });
    }
  }
}
