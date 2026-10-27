package com.ken.platform.foundation.config;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.ServiceHttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * foundation 公共装配入口。
 *
 * <p>基础时钟、发现快照和 HTTP 客户端；Web、Nacos、数据能力分别条件装配。 技术依赖保持 optional，消费方显式引入后才启用。
 */
@AutoConfiguration
public class PlatformAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  Clock platformClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  ServiceDiscovery platformDiscovery() {
    return new ServiceDiscovery(System::nanoTime);
  }

  @Bean
  @ConditionalOnMissingBean
  RuntimeParameters platformParameters() {
    return RuntimeParameters.defaults();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.springframework.web.reactive.function.client.WebClient",
        "reactor.netty.http.client.HttpClient"
      })
  static class HttpConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ServiceHttpClient platformHttp(
        ServiceDiscovery discovery,
        Clock clock,
        RuntimeParameters parameters,
        org.springframework.beans.factory.ObjectProvider<
                com.ken.platform.foundation.http.HttpProtection>
            protection) {
      return ServiceHttpClient.configured(
          discovery,
          clock,
          parameters,
          protection.getIfAvailable(() -> com.ken.platform.foundation.http.HttpProtection.NONE));
    }
  }
}
