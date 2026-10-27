package com.ken.platform.foundation.data;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class DatabaseReadinessTest {
  @Test
  void unsafeUnknownOrUnavailableNeverPassesAndNeverEchoesDependencyError() {
    for (Boolean unsafe : new Boolean[] {true, null}) {
      var jdbc = mock(JdbcTemplate.class);
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(unsafe);
      assertThatThrownBy(() -> new DatabaseReadiness(jdbc, "runtime", "identity").verify())
          .isInstanceOf(IllegalStateException.class)
          .hasNoCause();
    }
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_DATABASE"));
    assertThatThrownBy(() -> new DatabaseReadiness(jdbc, "runtime", "identity").verify())
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageNotContaining("DO_NOT_ECHO_DATABASE");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
  void realRuntimePassesButWrongBindingAndMigratorMembershipAreRejected() throws Exception {
    var binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    for (boolean migrator : new boolean[] {false, true}) {
      var settings = new HikariConfig();
      settings.setJdbcUrl(
          "jdbc:postgresql://"
              + binding.get("host").asString()
              + ":"
              + binding.get("port").asInt()
              + "/"
              + binding.get("dbname").asString());
      String username = binding.get(migrator ? "observer_username" : "username").asString();
      settings.setUsername(username);
      settings.setPassword(
          Files.readString(
                  Path.of(
                      binding
                          .get(migrator ? "observer_password_file" : "password_file")
                          .asString()))
              .strip());
      settings.setMaximumPoolSize(1);
      try (var pool = new HikariDataSource(settings)) {
        var jdbc = new JdbcTemplate(pool);
        var check = new DatabaseReadiness(jdbc, username, binding.get("dbname").asString());
        if (migrator) assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class);
        else {
          check.afterSingletonsInstantiated();
          assertThatThrownBy(
                  () ->
                      new DatabaseReadiness(jdbc, "other_runtime", binding.get("dbname").asString())
                          .verify())
              .isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(() -> new DatabaseReadiness(jdbc, username, "other_database").verify())
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }
}
