package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlatformMetricsAutoConfigurationTest {
  @Test
  void collectorMustUseAnIndependentValidPort() {
    var env = new MockEnvironment().withProperty("SERVICE_BIND_PORT", "8081");
    for (String invalid : new String[] {"0", "65536", "8081"}) {
      env.setProperty("MANAGEMENT_SERVER_PORT", invalid);
      assertThatThrownBy(() -> PlatformMetricsAutoConfiguration.metricsPort(env))
          .isInstanceOf(IllegalArgumentException.class);
    }
    env.setProperty("MANAGEMENT_SERVER_PORT", "9081");
    assertThat(PlatformMetricsAutoConfiguration.metricsPort(env)).isEqualTo(9081);
  }
}
