package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LoginProtectionConfigurationTest {
  @TempDir Path temporary;

  private MockEnvironment environment(String app, Path file) {
    return new MockEnvironment()
        .withProperty("APP_ENV", app)
        .withProperty("REDIS_HOST", "127.0.0.1")
        .withProperty("REDIS_PORT", "16379")
        .withProperty("REDIS_PASSWORD_SECRET_FILE", file.toString());
  }

  @Test
  void missingAndEmptySecretOrRelativeReferencePreventStartup() throws Exception {
    var config = new LoginProtectionConfiguration();
    assertThatThrownBy(
            () ->
                config.identityRedisConnectionFactory(
                    new MockEnvironment().withProperty("APP_ENV", "local")))
        .isInstanceOf(IllegalStateException.class);
    Path file = temporary.resolve("empty");
    Files.writeString(file, " ");
    assertThatThrownBy(() -> config.identityRedisConnectionFactory(environment("local", file)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                config.identityRedisConnectionFactory(
                    environment("local", Path.of("relative-secret"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonLocalRequiresTlsAndAllCommandsHaveBoundedTimeout() throws Exception {
    Path file = temporary.resolve("password");
    Files.writeString(file, "fixture");
    var config = new LoginProtectionConfiguration();
    for (String app : java.util.List.of("local", "test", "prod")) {
      var factory = config.identityRedisConnectionFactory(environment(app, file));
      assertThat(factory.getClientConfiguration().isUseSsl()).isEqualTo(!app.equals("local"));
      assertThat(factory.getClientConfiguration().getCommandTimeout())
          .isEqualTo(Duration.ofSeconds(2));
    }
  }

  @Test
  void unknownEnvironmentAndInvalidEndpointPreventStartup() throws Exception {
    Path file = temporary.resolve("password");
    Files.writeString(file, "fixture");
    var config = new LoginProtectionConfiguration();
    assertThatThrownBy(() -> config.identityRedisConnectionFactory(environment("unknown", file)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                config.identityRedisConnectionFactory(
                    environment("local", file).withProperty("REDIS_HOST", "user@host")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                config.identityRedisConnectionFactory(
                    environment("local", file).withProperty("REDIS_PORT", "65536")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
