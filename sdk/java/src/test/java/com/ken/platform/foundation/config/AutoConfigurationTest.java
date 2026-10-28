package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.ServiceHttpClient;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
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
                "org.springframework.security") {
              @Override
              public Enumeration<URL> getResources(String name) throws IOException {
                var resources = super.getResources(name);
                if (!name.equals("META-INF/services/io.micrometer.context.ThreadLocalAccessor"))
                  return resources;
                // 模拟移除整个可选依赖：不能只隐藏类，却留下其 SPI 注册文件。
                return Collections.enumeration(
                    Collections.list(resources).stream()
                        .filter(resource -> !resource.toString().contains("spring-security-"))
                        .toList());
              }
            })
        .run(
            application -> {
              assertThat(application)
                  .hasNotFailed()
                  .hasSingleBean(ServiceDiscovery.class)
                  .hasSingleBean(ServiceHttpClient.class);
              assertThatThrownBy(
                      () ->
                          application
                              .getClassLoader()
                              .loadClass(
                                  "org.springframework.security.core.context.SecurityContextHolder"))
                  .isInstanceOf(ClassNotFoundException.class);
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
