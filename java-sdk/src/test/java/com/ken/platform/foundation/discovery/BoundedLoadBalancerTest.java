package com.ken.platform.foundation.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedLoadBalancerTest {
  @Test
  void supplierCannotExtendConfirmationOrReturnExpiredSdkCache() {
    var clock = new AtomicLong();
    var discovery = new ServiceDiscovery(clock::get);
    discovery.confirm("analysis", List.of(new ServiceInstance("192.0.2.7", 8100, "https")));
    var runtime = mock(NacosRuntime.class);
    var supplier =
        new BoundedServiceInstanceListSupplier(
            "analysis", discovery, runtime, Clock.systemUTC(), RuntimeParameters.defaults());
    assertThat(supplier.get().blockFirst())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getHost()).isEqualTo("192.0.2.7");
              assertThat(instance.isSecure()).isTrue();
            });
    clock.set(Duration.ofSeconds(61).toNanos());
    assertThatThrownBy(() -> supplier.get().blockFirst()).isInstanceOf(PlatformException.class);
  }

  @Test
  void expiredRequestDoesNotStartDirectoryIo() {
    var runtime = mock(NacosRuntime.class);
    var supplier =
        new BoundedServiceInstanceListSupplier(
            "analysis",
            new ServiceDiscovery(System::nanoTime),
            runtime,
            Clock.systemUTC(),
            RuntimeParameters.defaults());
    var context =
        new CallContext("request-1", null, null, null, null, Instant.now().minusSeconds(1));
    assertThatThrownBy(
            () ->
                supplier
                    .get()
                    .contextWrite(value -> value.put(CallContext.class, context))
                    .blockFirst())
        .isInstanceOf(PlatformException.class);
    verifyNoInteractions(runtime);
  }
}
