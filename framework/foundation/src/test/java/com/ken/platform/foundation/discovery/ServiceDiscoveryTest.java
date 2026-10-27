package com.ken.platform.foundation.discovery;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ServiceDiscoveryTest {
  @Test
  void instanceHostCannotInjectCredentialsOrPath() {
    for (String host :
        List.of("other.example/private", "user@other.example", "other.example?x=1")) {
      assertThatThrownBy(() -> new ServiceInstance(host, 8100, "http"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void expiryUsesLastNetworkConfirmationAndInvalidationDoesNotExtendIt() {
    var clock = new AtomicLong();
    var directory = new ServiceDiscovery(clock::get);
    var first = new ServiceInstance("192.0.2.1", 8100, "http");
    var second = new ServiceInstance("192.0.2.2", 8100, "http");
    directory.confirm("analysis", List.of(first, second));
    clock.set(Duration.ofSeconds(60).toNanos());
    directory.invalidate("analysis", first);
    assertThat(directory.choose("analysis")).isEqualTo(second);
    clock.incrementAndGet();
    assertThatThrownBy(() -> directory.choose("analysis")).isInstanceOf(PlatformException.class);
    directory.confirm("analysis", List.of(second));
    assertThat(directory.choose("analysis")).isEqualTo(second);
    directory.confirm("analysis", List.of());
    assertThatThrownBy(() -> directory.choose("analysis")).isInstanceOf(PlatformException.class);
  }
}
