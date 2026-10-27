package com.ken.platform.identity.service.login;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.service.security.SecurityPolicies.Effective;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class LoginProtectionTest {
  private LoginRedisFixture f;
  private InetAddress ip;

  private Effective policy(int threshold, int window, int ipLimit) {
    return new Effective(15, threshold, window, 900, ipLimit, 60, 28800, 1800, 300, 0L, null);
  }

  private final Effective base = policy(2, 900, 60);

  @BeforeEach
  void setup() throws Exception {
    f = new LoginRedisFixture();
    ip = InetAddress.getLoopbackAddress();
  }

  @AfterEach
  void cleanup() {
    if (f != null) f.close();
  }

  @Test
  void thresholdLocksWithoutExtensionAndExpiryClearsTheOldRound() {
    var first = f.protection.begin("TENANT|a|same-user", ip, base);
    f.protection.failure(first, base);
    var second = f.protection.begin("TENANT|a|same-user", ip, base);
    assertThatThrownBy(() -> f.protection.failure(second, base))
        .isInstanceOfSatisfying(
            LoginProtection.Limited.class,
            e -> assertThat(e.retryAfterSeconds()).isBetween(899L, 900L));
    String until = (String) f.redis.opsForHash().get(first.keys().get(2), "until");
    assertThatThrownBy(() -> f.protection.begin("TENANT|a|same-user", ip, base))
        .isInstanceOf(LoginProtection.Limited.class);
    assertThat(f.redis.opsForHash().get(first.keys().get(2), "until")).isEqualTo(until);
    f.redis
        .opsForHash()
        .put(first.keys().get(2), "until", Long.toString(System.currentTimeMillis() - 1));
    var next = f.protection.begin("TENANT|a|same-user", ip, base);
    assertThat(f.redis.opsForZSet().zCard(first.keys().get(1))).isZero();
    f.protection.failure(next, base);
  }

  @Test
  void successClearsFailuresButKeepsIpAndReplayCannotClearNewerFailures() {
    var first = f.protection.begin("TENANT|a|user", ip, base);
    f.protection.failure(first, base);
    var success = f.protection.begin("TENANT|a|user", ip, base);
    f.protection.success(success, base);
    assertThat(f.redis.opsForZSet().zCard(first.keys().get(1))).isZero();
    assertThat(f.redis.opsForZSet().zCard(first.keys().get(0))).isEqualTo(2);
    var later = f.protection.begin("TENANT|a|user", ip, base);
    f.protection.failure(later, base);
    f.protection.success(success, base);
    f.protection.failure(first, base);
    assertThat(f.redis.opsForZSet().zCard(first.keys().get(1))).isEqualTo(1);
    assertThat(f.redis.opsForZSet().zCard(first.keys().get(0))).isEqualTo(3);
  }

  @Test
  void accountBucketsAreIsolatedWhileIpRequestsAreGlobal() {
    var p = policy(2, 900, 3);
    var a = f.protection.begin("TENANT|a|same-user", ip, p);
    f.protection.failure(a, p);
    var b = f.protection.begin("TENANT|b|same-user", ip, p);
    f.protection.failure(b, p);
    var platform = f.protection.begin("PLATFORM||same-user", ip, p);
    assertThat(a.keys().get(1)).isNotEqualTo(b.keys().get(1));
    assertThat(platform.keys().get(1)).isNotEqualTo(a.keys().get(1));
    assertThat(a.keys()).noneMatch(key -> key.contains("same-user"));
    assertThatThrownBy(() -> f.protection.begin("TENANT|another|unknown", ip, p))
        .isInstanceOf(LoginProtection.Limited.class);
  }

  @Test
  void longerCurrentWindowRetainsOlderFailuresAndCanApplyTighterThreshold() {
    var first = f.protection.begin("TENANT|a|user", ip, base);
    f.protection.failure(first, base);
    f.redis
        .opsForZSet()
        .add(first.keys().get(1), first.nonce(), System.currentTimeMillis() - 1000000);
    var next = f.protection.begin("TENANT|a|user", ip, base);
    f.protection.failure(next, base);
    assertThatThrownBy(() -> f.protection.begin("TENANT|a|user", ip, policy(2, 3600, 60)))
        .isInstanceOf(LoginProtection.Limited.class);
  }

  @Test
  void concurrentFailuresCreateOneFixedLockAtTheThreshold() throws Exception {
    AtomicInteger ordinary = new AtomicInteger(), limited = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            try {
                              var ticket = f.protection.begin("TENANT|a|user", ip, base);
                              f.protection.failure(ticket, base);
                              ordinary.incrementAndGet();
                            } catch (LoginProtection.Limited rejected) {
                              limited.incrementAndGet();
                            }
                          }))
              .toList();
      for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(ordinary.get()).isEqualTo(1);
    assertThat(limited.get()).isEqualTo(19);
    var keys = f.redis.keys(f.project + ":*failures:*");
    assertThat(keys).hasSize(1);
    assertThat(f.redis.opsForZSet().zCard(keys.iterator().next())).isEqualTo(2);
  }

  @Test
  void redisFailureNeverReturnsAnAdmission() {
    var broken =
        new org.springframework.data.redis.core.StringRedisTemplate() {
          @Override
          public <T> T execute(
              org.springframework.data.redis.core.script.RedisScript<T> script,
              java.util.List<String> keys,
              Object... arguments) {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                "injected secret must not escape");
          }
        };
    var protection = new LoginProtection(broken, f.project, "local");
    assertThatThrownBy(() -> protection.begin("TENANT|a|user", ip, base))
        .isInstanceOfSatisfying(
            com.ken.platform.foundation.http.PlatformException.class,
            e -> {
              assertThat(e.code()).isEqualTo("LOGIN_PROTECTION_UNAVAILABLE");
              assertThat(e.status()).isEqualTo(503);
            });
  }
}
