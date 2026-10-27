package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class PlatformDatabaseSettingsTest {
  @TempDir Path directory;

  private MockEnvironment environment() throws Exception {
    Path secret = directory.resolve("database.password");
    Files.writeString(secret, "private-database-fixture\n");
    return new MockEnvironment()
        .withProperty("APP_ENV", "local")
        .withProperty("PG_HOST", "127.0.0.1")
        .withProperty("PG_PORT", "15432")
        .withProperty("DB_NAME", "sample_local_identity")
        .withProperty("DB_USER", "sample_local_identity_runtime")
        .withProperty("DB_PASSWORD_SECRET_FILE", secret.toString());
  }

  @Test
  void controlledReferenceCreatesPostgresPropertiesAndDisablesRuntimeDdl() throws Exception {
    var settings = PlatformDatabaseSettings.from(environment());
    var values = settings.springProperties();
    assertThat(values.get("spring.datasource.url").toString())
        .startsWith("jdbc:postgresql://127.0.0.1:15432/sample_local_identity?")
        .contains("sslmode=disable", "connectTimeout=2", "socketTimeout=10");
    assertThat(values)
        .containsEntry("spring.datasource.password", "private-database-fixture")
        .containsEntry("spring.flyway.enabled", false)
        .containsEntry("spring.sql.init.mode", "never")
        .containsEntry("spring.jpa.hibernate.ddl-auto", "none");
    assertThat(settings.toString()).doesNotContain("private-database-fixture", "database.password");
  }

  @Test
  void nonlocalUsesVerificationAndJvmTrustWhileIpv6StaysBracketed() throws Exception {
    var env = environment().withProperty("APP_ENV", "prod").withProperty("PG_HOST", "::1");
    assertThat(
            PlatformDatabaseSettings.from(env)
                .springProperties()
                .get("spring.datasource.url")
                .toString())
        .startsWith("jdbc:postgresql://[::1]:15432/")
        .contains("sslmode=verify-full", "org.postgresql.ssl.DefaultJavaSSLFactory")
        .doesNotContain("sslmode=disable", "private-database-fixture");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "host",
        "port",
        "database",
        "user",
        "relative",
        "missing",
        "empty",
        "environment",
        "literal",
        "url",
        "spring",
        "hikari"
      })
  void malformedOrSecondSourceNeverFallsBack(String scenario) throws Exception {
    var env = environment();
    switch (scenario) {
      case "host" -> env.setProperty("PG_HOST", "db.invalid/path?password=DO_NOT_ECHO");
      case "port" -> env.setProperty("PG_PORT", "65536");
      case "database" -> env.setProperty("DB_NAME", "identity?sslmode=disable");
      case "user" -> env.setProperty("DB_USER", "../../owner");
      case "relative" -> env.setProperty("DB_PASSWORD_SECRET_FILE", "database.password");
      case "missing" ->
          env.setProperty("DB_PASSWORD_SECRET_FILE", directory.resolve("missing").toString());
      case "empty" -> Files.writeString(Path.of(env.getProperty("DB_PASSWORD_SECRET_FILE")), "  ");
      case "environment" -> env.setProperty("APP_ENV", "production");
      case "literal" -> env.setProperty("DB_PASSWORD", "DO_NOT_ECHO");
      case "url" -> env.setProperty("JDBC_URL", "DO_NOT_ECHO");
      case "spring" -> env.setProperty("spring.datasource.url", "DO_NOT_ECHO");
      default ->
          env.setProperty("spring.datasource.hikari.data-source-properties.sslmode", "disable");
    }
    var failure = catchThrowable(() -> PlatformDatabaseSettings.from(env));
    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    assertThat(failure.getMessage()).doesNotContain("DO_NOT_ECHO", "private-database-fixture");
  }
}
