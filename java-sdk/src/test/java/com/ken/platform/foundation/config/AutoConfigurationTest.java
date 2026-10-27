package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.ServiceHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AutoConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PlatformAutoConfiguration.class,
                  PlatformWebAutoConfiguration.class,
                  PlatformResourceSecurityAutoConfiguration.class,
                  PlatformDataAutoConfiguration.class,
                  PlatformMybatisAutoConfiguration.class));

  @Test
  void mybatisRegistersPostgresUuidHandlerWhenPresent() {
    context.run(
        application -> {
          assertThat(application).hasNotFailed();
          var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
          application
              .getBean(com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer.class)
              .customize(configuration);
          assertThat(configuration.getTypeHandlerRegistry().getTypeHandler(java.util.UUID.class))
              .isInstanceOf(com.ken.platform.foundation.data.UuidTypeHandler.class);
        });
  }

  @Test
  void httpWorksWithoutServletOrDatabase() {
    context
        .withClassLoader(
            new FilteredClassLoader(
                "jakarta.servlet",
                "org.springframework.jdbc",
                "com.baomidou",
                "org.springframework.security"))
        .run(
            application -> {
              assertThat(application)
                  .hasNotFailed()
                  .hasSingleBean(ServiceDiscovery.class)
                  .hasSingleBean(ServiceHttpClient.class);
            });
  }

  @Test
  void httpIsOptional() {
    context
        .withClassLoader(
            new FilteredClassLoader("org.springframework.web.reactive", "reactor.netty"))
        .run(
            application -> {
              assertThat(application)
                  .hasNotFailed()
                  .hasSingleBean(ServiceDiscovery.class)
                  .doesNotHaveBean(ServiceHttpClient.class);
            });
  }
}
