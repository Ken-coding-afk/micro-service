package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

/** 新增 BFF 机制测试共用的真实 Redis 夹具，仅清理本轮随机前缀。 */
final class BffRedisFixture implements AutoCloseable {
  final String project = "bff-protocol-test-" + UUID.randomUUID();
  final LettuceConnectionFactory connection;
  final ReactiveRedisTemplate<String, Object> sessions;
  final ReactiveStringRedisTemplate strings;

  BffRedisFixture() throws Exception {
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
    var serializer = BffSessions.serializer();
    sessions =
        new ReactiveRedisTemplate<>(
            connection,
            RedisSerializationContext.<String, Object>newSerializationContext(
                    new StringRedisSerializer())
                .value(serializer)
                .hashKey(new StringRedisSerializer())
                .hashValue(serializer)
                .build());
    strings = new ReactiveStringRedisTemplate(connection);
  }

  @Override
  public void close() {
    try {
      var cleanup = new StringRedisTemplate(connection);
      var keys = cleanup.keys(project + ":*");
      if (keys != null && !keys.isEmpty()) {
        assertThat(keys).allMatch(key -> key.startsWith(project + ":"));
        cleanup.delete(keys);
      }
    } finally {
      connection.destroy();
    }
  }
}
