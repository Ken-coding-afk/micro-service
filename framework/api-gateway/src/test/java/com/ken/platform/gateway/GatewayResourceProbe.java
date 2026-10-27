package com.ken.platform.gateway;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.gateway.config.ExternalBoundaryFilter;
import com.ken.platform.gateway.security.GatewayForwardedHeaderFilter;
import com.ken.platform.gateway.security.GatewayPrincipalContextFilter;
import com.ken.platform.gateway.security.GatewaySecurityConfiguration;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.reactive.context.AnnotationConfigReactiveWebServerApplicationContext;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/**
 * 仅测试：加载实际 Gateway 制品/路由/认证/Netty；发现是指定真实身份 HTTP 地址的夹具， 不作 Nacos/完整应用启动验收，不伪造主体、不跳过真实内省。stdin
 * 触发正常关闭。
 */
public final class GatewayResourceProbe {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableWebFluxSecurity
  @Import({
    GatewaySecurityConfiguration.class,
    ExternalBoundaryFilter.class,
    GatewayForwardedHeaderFilter.class,
    GatewayPrincipalContextFilter.class
  })
  @LoadBalancerClient(name = "identity-service", configuration = IdentityDirectory.class)
  static class Fixture {
    @Bean
    RuntimeParameters platformParameters() {
      return RouteFixtures.parameters(
          RouteFixtures.route(
              "identity-api", "/api/v1/identity/**", "identity-service", "identity-service"));
    }

    @Bean
    org.springframework.cloud.gateway.config.GatewayProperties gatewayProperties(
        RuntimeParameters parameters) {
      return RouteFixtures.properties(parameters);
    }

    @Bean
    ServiceDiscovery platformDiscovery(Environment environment) {
      var directory = new ServiceDiscovery(System::nanoTime);
      var target = URI.create(environment.getRequiredProperty("probe.identity-origin"));
      directory.confirm(
          "identity-service",
          List.of(new ServiceInstance(target.getHost(), target.getPort(), "http")));
      return directory;
    }

    // 无生命周期注册：此夹具只固定目录，不创建/连接假身份服务。
    @Bean
    NacosRuntime runtime(ServiceDiscovery directory) {
      return new NacosRuntime(null, null, null, directory);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class IdentityDirectory {
    @Bean
    ServiceInstanceListSupplier identityInstances(Environment environment) {
      var target = URI.create(environment.getRequiredProperty("probe.identity-origin"));
      return new ServiceInstanceListSupplier() {
        @Override
        public String getServiceId() {
          return "identity-service";
        }

        @Override
        public Flux<List<org.springframework.cloud.client.ServiceInstance>> get() {
          return Flux.just(
              List.of(
                  new DefaultServiceInstance(
                      "identity-native-http",
                      "identity-service",
                      target.getHost(),
                      target.getPort(),
                      false)));
        }
      };
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("需要隔离测试绑定");
    var json = JsonMapper.builder().build();
    var binding = json.readTree(Files.readString(Path.of(args[0])));
    if (!binding.get("test_only").asBoolean())
      throw new IllegalArgumentException("仅接受 test_only 绑定");
    var target = URI.create(binding.get("identity_origin").asString());
    if (!"http".equals(target.getScheme())
        || !"127.0.0.1".equals(target.getHost())
        || target.getPort() < 1) throw new IllegalArgumentException("该探针仅用于 loopback 身份测试");
    try (var application = new AnnotationConfigReactiveWebServerApplicationContext()) {
      application
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test-only-probe",
                  Map.ofEntries(
                      Map.entry("APP_ENV", "local"),
                      Map.entry("PUBLIC_ORIGIN", binding.get("issuer").asString()),
                      Map.entry("SERVICE_NAME", "api-gateway"),
                      Map.entry("server.address", "127.0.0.1"),
                      Map.entry("server.port", "0"),
                      Map.entry("server.forward-headers-strategy", "none"),
                      Map.entry("spring.cloud.nacos.discovery.enabled", "false"),
                      Map.entry("spring.cloud.nacos.config.enabled", "false"),
                      Map.entry(
                          "spring.cloud.gateway.server.webflux.discovery.locator.enabled", "false"),
                      Map.entry("probe.identity-origin", target.toString()),
                      Map.entry(
                          "GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE",
                          binding.get("readers_file").asString()))));
      application.register(Fixture.class);
      application.refresh();
      Files.writeString(
          Path.of(binding.get("ready_file").asString()),
          json.writeValueAsString(
              Map.of(
                  "test_only",
                  true,
                  "origin",
                  "http://127.0.0.1:" + application.getWebServer().getPort())));
      System.in.read();
    }
  }
}
