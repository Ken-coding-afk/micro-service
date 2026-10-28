package com.ken.platform.identity.dal.support;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class IdentityTenantScopeTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private IdentityTenantScope scope;
  private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    var binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
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
    config.setMaximumPoolSize(1);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    var manager = new JdbcTransactionManager(pool);
    tx = new TransactionTemplate(manager);
    scope = new IdentityTenantScope(jdbc, manager);
    assertThat(
            jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class))
        .isFalse();
    for (UUID tenant : new UUID[] {a, b}) {
      jdbc.update(
          "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'scope-test')",
          tenant,
          "scope-" + tenant);
      tx.execute(
          status ->
              scope.within(
                  tenant,
                  () ->
                      jdbc.update(
                          "INSERT INTO sys_user(tenant_id,normalized_username,display_name,password_hash) VALUES(?,'scope-user','Scope','unused')",
                          tenant)));
    }
  }

  @AfterEach
  void close() {
    if (pool != null) pool.close();
  }

  private String selected() {
    return jdbc.queryForObject(
        "SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class);
  }

  @Test
  void nestedScopeRestoresAndOneConnectionNeverLeaksTenantRows() {
    int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    for (UUID tenant : new UUID[] {a, b}) {
      tx.executeWithoutResult(
          status -> {
            assertThat(selected()).isEmpty();
            scope.within(
                tenant,
                () -> {
                  assertThat(
                          jdbc.queryForList("SELECT DISTINCT tenant_id FROM sys_user", UUID.class))
                      .containsExactly(tenant);
                  scope.within(
                      tenant,
                      () -> {
                        assertThat(selected()).isEqualTo(tenant.toString());
                        return null;
                      });
                  assertThat(selected()).isEqualTo(tenant.toString());
                  return null;
                });
            assertThat(selected()).isEmpty();
          });
      assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
      assertThat(selected()).isEmpty();
      assertThat(jdbc.queryForList("SELECT id FROM sys_user")).isEmpty();
    }
    assertThatThrownBy(() -> scope.within(a, () -> null))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void crossTenantAndPlatformNestingRejectAndRollback() {
    for (UUID next : new UUID[] {b, null}) {
      assertThatThrownBy(
              () -> tx.execute(status -> scope.within(a, () -> scope.within(next, () -> null))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("身份事务不能切换租户");
      assertThat(selected()).isEmpty();
    }
  }

  @Test
  void sqlFailurePreservesOriginalErrorAndCaughtFailureCannotCommit() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        scope.within(a, () -> jdbc.queryForObject("SELECT 1/0", Integer.class))))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            error ->
                assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                    .isEqualTo("22012"));
    var original = new IllegalArgumentException("scope-operation-failed");
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      try {
                        scope.within(
                            a,
                            () -> {
                              jdbc.update(
                                  "UPDATE sys_tenant SET name='must-rollback' WHERE id=?", a);
                              throw original;
                            });
                      } catch (IllegalArgumentException caught) {
                        assertThat(caught).isSameAs(original);
                      }
                      return null;
                    }))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(jdbc.queryForObject("SELECT name FROM sys_tenant WHERE id=?", String.class, a))
        .isEqualTo("scope-test");
    assertThat(selected()).isEmpty();
  }
}
