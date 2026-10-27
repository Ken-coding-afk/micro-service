package com.ken.platform.foundation.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.ken.platform.foundation.resilience.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(
    after = PlatformAutoConfiguration.class,
    before = PlatformNacosAutoConfiguration.class)
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource")
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class PlatformSentinelAutoConfiguration {
  @Bean
  SentinelProtection platformSentinelProtection() {
    return new SentinelProtection();
  }

  @Bean
  SentinelRuleSource platformSentinelRules(NacosSettings settings, NacosConfigManager manager) {
    return new SentinelRuleSource(settings, manager.getConfigService());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class Servlet {
    @Bean
    ServletAdmissionFilter platformAdmission(SentinelProtection protection) {
      return new ServletAdmissionFilter(protection);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class Reactive {
    @Bean
    ReactiveAdmissionFilter platformAdmission(SentinelProtection protection) {
      return new ReactiveAdmissionFilter(protection);
    }
  }
}
