package com.ken.platform.foundation.data;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class TenantIsolationTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions tenants;
  private JsonNode values;

  @BeforeEach
  void open() throws Exception {
    values =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(values.get("test_only").asBoolean()).isTrue();
    assertThat(values.get("app_env").asString()).isEqualTo("local");
    assertThat(values.get("dbname").asString()).startsWith("t02t");
    var configuration = new HikariConfig();
    configuration.setJdbcUrl(
        "jdbc:postgresql://"
            + values.get("host").asString()
            + ":"
            + values.get("port").asInt()
            + "/"
            + values.get("dbname").asString());
    configuration.setUsername(values.get("username").asString());
    configuration.setPassword(
        Files.readString(Path.of(values.get("password_file").asString())).strip());
    configuration.setMaximumPoolSize(1);
    configuration.setMinimumIdle(1);
    pool = new HikariDataSource(configuration);
    jdbc = new JdbcTemplate(pool);
    tenants = new TenantTransactions(jdbc, new JdbcTransactionManager(pool));
  }

  @AfterEach
  void close() {
    if (pool != null) {
      pool.close();
    }
  }

  private CallContexts.Scope tenant(String suffix) {
    return CallContexts.bind(
        new CallContext(
            "test-identity",
            UUID.fromString(values.get("tenant_" + suffix).asString()),
            values.get("user_" + suffix).asString(),
            null,
            null,
            null));
  }

  @Test
  void sameConnectionRestoresIsolationAfterExceptionAndReuse() {
    Integer pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class)).isZero();
    try (var ignored = tenant("a")) {
      assertThat(
              tenants.execute(
                  () -> jdbc.queryForList("SELECT id::text FROM sys_user", String.class)))
          .containsExactly(values.get("user_a").asString());
      assertThatThrownBy(
              () ->
                  tenants.execute(
                      () -> {
                        jdbc.update("UPDATE sys_user SET display_name='must-rollback'");
                        throw new IllegalStateException("rollback");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              tenants.execute(
                  () -> jdbc.queryForObject("SELECT display_name FROM sys_user", String.class)))
          .isEqualTo("用户");
    }
    assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class)).isZero();
    try (var ignored = tenant("b")) {
      assertThat(
              tenants.execute(
                  () -> jdbc.queryForList("SELECT id::text FROM sys_user", String.class)))
          .containsExactly(values.get("user_b").asString());
    }
  }

  @Test
  void missingContextAndCrossTenantWriteCannotProceed() {
    assertThatThrownBy(() -> tenants.execute(() -> "forbidden"))
        .isInstanceOf(PlatformException.class);
    try (var ignored = tenant("a")) {
      assertThatThrownBy(
              () ->
                  tenants.execute(
                      () ->
                          jdbc.update(
                              "INSERT INTO sys_user(tenant_id,normalized_username,display_name,password_hash) VALUES (?,'forged','伪造','fixture')",
                              UUID.fromString(values.get("tenant_b").asString()))))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class)).isZero();
  }
}
