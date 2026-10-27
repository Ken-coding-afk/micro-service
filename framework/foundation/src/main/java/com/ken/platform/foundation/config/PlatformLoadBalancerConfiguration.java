package com.ken.platform.foundation.config;

import com.ken.platform.foundation.discovery.BoundedServiceInstanceListSupplier;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import java.time.Clock;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 仅由 LoadBalancerClients 的服务子上下文加载，禁止作为全局组件扫描。 */
public class PlatformLoadBalancerConfiguration {
  @Bean
  ServiceInstanceListSupplier boundedServiceInstances(
      Environment environment,
      ServiceDiscovery discovery,
      NacosRuntime runtime,
      Clock clock,
      RuntimeParameters parameters) {
    return new BoundedServiceInstanceListSupplier(
        environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME),
        discovery,
        runtime,
        clock,
        parameters);
  }
}
