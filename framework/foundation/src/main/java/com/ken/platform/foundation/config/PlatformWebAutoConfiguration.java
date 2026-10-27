package com.ken.platform.foundation.config;

import com.ken.platform.foundation.web.ReactiveRequestContextFilter;
import com.ken.platform.foundation.web.ServletRequestContextFilter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(after = PlatformAutoConfiguration.class)
public class PlatformWebAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(
      name = {"org.springframework.web.server.WebFilter", "tools.jackson.databind.json.JsonMapper"})
  static class Reactive {
    @Bean
    @ConditionalOnMissingBean
    ReactiveRequestContextFilter platformRequestContextFilter(
        Clock clock, RuntimeParameters parameters) {
      return new ReactiveRequestContextFilter(clock, parameters);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = {"jakarta.servlet.Filter", "tools.jackson.databind.json.JsonMapper"})
  static class Servlet {
    @Bean
    @ConditionalOnMissingBean
    ServletRequestContextFilter platformRequestContextFilter(
        Clock clock, RuntimeParameters parameters) {
      return new ServletRequestContextFilter(clock, parameters);
    }
  }
}
