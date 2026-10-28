package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class MonitoringMetricsBindingTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MetricsProperties.class)
  static class Binding {}

  private ApplicationContextRunner environment(String field) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "systemEnvironment", Map.of(field, (Object) "true"))));
  }

  @Test
  void runtimeFlagEnablesTheHttpRequestHistogram() {
    environment("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(MetricsProperties.class)
                          .getDistribution()
                          .getPercentilesHistogram())
                  .containsEntry("http.server.requests", true);
            });
  }

  @Test
  void extraSeparatorIsAConflictingPercentileArrayRatherThanAHistogramFlag() {
    environment("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS")
        .run(context -> assertThat(context).hasFailed());
  }
}
