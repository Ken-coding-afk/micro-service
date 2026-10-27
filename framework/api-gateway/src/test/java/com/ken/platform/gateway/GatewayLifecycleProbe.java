package com.ken.platform.gateway;

import org.springframework.boot.SpringApplication;

/** 仅集成测试使用 stdin 触发正常关闭；不打入应用镜像。 */
public final class GatewayLifecycleProbe {
  public static void main(String[] args) throws Exception {
    try (var application = SpringApplication.run(GatewayApplication.class, args)) {
      application.getBean(com.ken.platform.gateway.security.GatewayForwardedHeaderFilter.class);
      if (!"none"
          .equals(application.getEnvironment().getProperty("server.forward-headers-strategy")))
        throw new IllegalStateException("真实 TCP 对端不能提前被转发头转换");
      var factory =
          application.getBean(
              org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory.class);
      var supplier =
          factory.getInstance(
              "api-gateway",
              org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier.class);
      if (!(supplier
          instanceof com.ken.platform.foundation.discovery.BoundedServiceInstanceListSupplier)) {
        throw new IllegalStateException("Gateway 必须使用有界实例目录");
      }
      System.in.read();
    }
  }
}
