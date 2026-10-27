package com.ken.platform.identity.service.login;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

final class LoginRedisFixture implements AutoCloseable {
  final String project = "login-test-" + UUID.randomUUID();
  final LettuceConnectionFactory connection;
  final StringRedisTemplate redis;
  final LoginProtection protection;

  LoginRedisFixture() throws Exception {
    var b =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_REDIS_BINDING"))));
    assertThat(b.get("test_only").asBoolean()).isTrue();
    assertThat(b.get("app_env").asString()).isEqualTo("local");
    var server = new RedisStandaloneConfiguration(b.get("host").asString(), b.get("port").asInt());
    server.setPassword(
        RedisPassword.of(Files.readString(Path.of(b.get("password_file").asString())).strip()));
    connection =
        new LettuceConnectionFactory(
            server,
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    connection.afterPropertiesSet();
    connection.start();
    redis = new StringRedisTemplate(connection);
    try (var check = redis.getConnectionFactory().getConnection()) {
      assertThat(check.ping()).isEqualTo("PONG");
    }
    protection = new LoginProtection(redis, project, "local");
  }

  @Override
  public void close() {
    try {
      var keys = redis.keys(project + ":*");
      if (keys != null && !keys.isEmpty()) {
        assertThat(keys).allMatch(key -> key.startsWith(project + ":"));
        redis.delete(keys);
      }
    } finally {
      connection.destroy();
    }
  }
}
