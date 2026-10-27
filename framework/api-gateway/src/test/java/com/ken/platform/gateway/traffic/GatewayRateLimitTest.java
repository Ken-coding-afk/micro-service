package com.ken.platform.gateway.traffic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 真实隔离 Redis、两个独立网关限流实例；不冒充两台物理机器验收。 */
class GatewayRateLimitTest {
  static String container;
  static LettuceConnectionFactory firstFactory, secondFactory;
  static PlatformRedisRateLimiter first, second;

  static String docker(String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("docker");
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output =
        new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .strip();
    if (process.waitFor() != 0) throw new IllegalStateException("隔离 Redis 测试命令失败: " + output);
    return output;
  }

  @BeforeAll
  static void start() throws Exception {
    container = "micro-resilience-test-" + UUID.randomUUID().toString().substring(0, 8);
    docker(
        "run",
        "-d",
        "--rm",
        "--name",
        container,
        "-p",
        "127.0.0.1::6379",
        "redis:7.2.16-alpine",
        "redis-server",
        "--save",
        "",
        "--appendonly",
        "no");
    String address = docker("port", container, "6379/tcp");
    int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    firstFactory = factory(port);
    secondFactory = factory(port);
    first = limiter(firstFactory);
    second = limiter(secondFactory);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .ignoreExceptions()
        .until(
            () ->
                "PONG"
                    .equals(
                        new ReactiveStringRedisTemplate(firstFactory)
                            .execute(connection -> connection.ping())
                            .next()
                            .block(Duration.ofSeconds(1))));
  }

  static LettuceConnectionFactory factory(int port) {
    var client =
        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(200))
            .build();
    var factory =
        new LettuceConnectionFactory(
            new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                "127.0.0.1", port),
            client);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static PlatformRedisRateLimiter limiter(LettuceConnectionFactory factory) {
    var script = new DefaultRedisScript();
    script.setLocation(new ClassPathResource("META-INF/scripts/request_rate_limiter.lua"));
    script.setResultType(List.class);
    var limiter =
        new PlatformRedisRateLimiter(
            new ReactiveStringRedisTemplate(factory), script, mock(ConfigurationService.class));
    limiter
        .getConfig()
        .put(
            "documents",
            new RedisRateLimiter.Config()
                .setReplenishRate(1)
                .setBurstCapacity(100)
                .setRequestedTokens(100));
    return limiter;
  }

  @AfterAll
  static void close() throws Exception {
    if (firstFactory != null) firstFactory.destroy();
    if (secondFactory != null) secondFactory.destroy();
    if (container != null) {
      var p = new ProcessBuilder("docker", "rm", "-f", container).redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      p.waitFor();
    }
  }

  @Test
  void canonicalAndBffShareAtomicBucketAcrossInstancesAndTenantKeysAreIsolated() {
    String key =
        new RateSubject("user", "tenant-a", "user-1", null).key("project", "local", "documents");
    assertThat(first.isAllowed("documents", key).block(Duration.ofSeconds(2)).isAllowed()).isTrue();
    assertThatThrownBy(
            () -> second.isAllowed("framework-bff-documents", key).block(Duration.ofSeconds(2)))
        .isInstanceOf(PlatformRedisRateLimiter.Rejected.class)
        .satisfies(
            error ->
                assertThat(((PlatformRedisRateLimiter.Rejected) error).retryAfter())
                    .isEqualTo(100));
    String other =
        new RateSubject("user", "tenant-b", "user-1", null).key("project", "local", "documents");
    assertThat(second.isAllowed("documents", other).block(Duration.ofSeconds(2)).isAllowed())
        .isTrue();
    assertThat(
            new RateSubject("service", null, "user-1", "tenant-a")
                .key("project", "local", "documents"))
        .isNotEqualTo(key);
    assertThat(
            new RateSubject("platform", null, "tenant-a", null)
                .key("project", "local", "documents"))
        .isNotEqualTo(key);
    assertThatThrownBy(
            () ->
                new RateSubject("user", null, "user-1", null).key("project", "local", "documents"))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void nativeFailOpenMarkerBecomes503EvenWhenBffIsAbsent() throws Exception {
    docker("pause", container);
    try {
      assertThatThrownBy(() -> first.isAllowed("documents", "outage").block(Duration.ofSeconds(3)))
          .isInstanceOf(PlatformException.class)
          .satisfies(
              error ->
                  assertThat(((PlatformException) error).code())
                      .isEqualTo("RATE_LIMIT_UNAVAILABLE"));
    } finally {
      docker("unpause", container);
    }
  }
}
