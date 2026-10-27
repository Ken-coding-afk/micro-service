package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class EnvironmentBindingTest {
  @PlatformService("analysis-service")
  static class ServiceFixture {}

  @TempDir Path temporary;

  private StandardEnvironment environment() throws Exception {
    Path password = temporary.resolve("nacos.password");
    Files.writeString(password, "private-fixture");
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "fixture",
                Map.ofEntries(
                    Map.entry("PROJECT_CODE", "sample"),
                    Map.entry("APP_ENV", "local"),
                    Map.entry("SERVICE_NAME", "analysis-service"),
                    Map.entry("SERVICE_BIND_PORT", "8100"),
                    Map.entry("SERVICE_ADVERTISE_HOST", "192.0.2.7"),
                    Map.entry("SERVICE_ADVERTISE_PORT", "18100"),
                    Map.entry("NACOS_SERVER_ADDR", "localhost:8848"),
                    Map.entry("NACOS_NAMESPACE", "sample-local"),
                    Map.entry("NACOS_RUNTIME_USER", "runtime"),
                    Map.entry("PG_HOST", "db.internal"),
                    Map.entry("PG_PORT", "5432"),
                    Map.entry("DB_NAME", "sample_local_analysis"),
                    Map.entry("DB_USER", "sample_local_analysis_runtime"),
                    Map.entry("DB_PASSWORD_SECRET_FILE", password.toString()),
                    Map.entry("NACOS_RUNTIME_PASSWORD_SECRET_FILE", password.toString()))));
    return environment;
  }

  @Test
  void bindsAdvertisedAndListeningPortsSeparatelyAndDisablesEarlyRegistration() throws Exception {
    var environment = environment();
    new PlatformEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication(ServiceFixture.class));
    assertThat(environment.getProperty("server.port")).isEqualTo("8100");
    assertThat(environment.getProperty("management.endpoint.health.probes.add-additional-paths"))
        .isEqualTo("true");
    assertThat(environment.getProperty("spring.cloud.nacos.discovery.register-enabled"))
        .isEqualTo("false");
    assertThat(
            NacosSettings.from(PlatformEnvironmentPostProcessor.bindings(environment))
                .advertisePort())
        .isEqualTo(18100);
    assertThat(environment.toString()).doesNotContain("private-fixture");
    assertThat(environment.getPropertySources().get("platform-environment").toString())
        .doesNotContain("private-fixture");
    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
    assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
  }

  @Test
  void rawForwardedHeadersCannotRewriteTcpPeerBeforeFrameworkTrustBoundary() throws Exception {
    var environment = environment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "unvalidated-web-option", Map.of("server.forward-headers-strategy", "framework")));
    new PlatformEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication(ServiceFixture.class));
    assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
  }

  @Test
  void environmentCannotRenameArtifactToAnotherService() throws Exception {
    var environment = environment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("wrong-artifact", Map.of("SERVICE_NAME", "api-gateway")));
    assertThatThrownBy(
            () ->
                new PlatformEnvironmentPostProcessor()
                    .postProcessEnvironment(
                        environment, new SpringApplication(ServiceFixture.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SERVICE_NAME");
  }

  @Test
  void rawNacosSpringImportIsRejected() throws Exception {
    var environment = environment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "invalid",
                Map.of("spring.config.import", "optional:nacos:application-shared.yaml")));
    assertThatThrownBy(
            () ->
                new PlatformEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void servicesWithoutJdbcDoNotRequireOrCreateDatabaseProperties() throws Exception {
    var environment = environment();
    var previous = Thread.currentThread().getContextClassLoader();
    try (var loader =
        new org.springframework.boot.test.context.FilteredClassLoader("org.springframework.jdbc")) {
      Thread.currentThread().setContextClassLoader(loader);
      new PlatformEnvironmentPostProcessor()
          .postProcessEnvironment(environment, new SpringApplication(ServiceFixture.class));
      assertThat(environment.getProperty("spring.datasource.url")).isNull();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }
}
