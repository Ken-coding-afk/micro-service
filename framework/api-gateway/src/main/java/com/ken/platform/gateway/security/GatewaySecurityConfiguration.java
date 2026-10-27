package com.ken.platform.gateway.security;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import java.time.Clock;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/** 显式路由与独立 audience 的异步 opaque 入口；BFF 在独立命名空间接续。 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfiguration {
  @Bean
  GatewayRoutes platformGatewayRoutes(RuntimeParameters parameters, GatewayProperties properties) {
    return new GatewayRoutes(properties);
  }

  @Bean(destroyMethod = "close")
  GatewayIntrospectionClients gatewayIntrospectionClients(
      Environment environment,
      RuntimeParameters parameters,
      GatewayRoutes routes,
      ServiceDiscovery discovery,
      Clock clock) {
    return new GatewayIntrospectionClients(environment, parameters, routes, discovery, clock);
  }

  @Bean
  org.springframework.cloud.gateway.route.RouteDefinitionLocator platformIdentityRoutes(
      GatewayRoutes routes) {
    return () -> reactor.core.publisher.Flux.fromIterable(routes.identityRoutes());
  }

  @Bean
  GatewayAuthenticationFilter platformRouteAuthentication(GatewayIntrospectionClients clients) {
    return new GatewayAuthenticationFilter(clients);
  }

  @Bean
  PlatformReadinessCheck gatewayDependencies(
      NacosRuntime runtime,
      RuntimeParameters parameters,
      GatewayRoutes routes,
      GatewayIntrospectionClients clients) {
    return () -> {
      try {
        if (!parameters.sameRestartFields(runtime.parameters()))
          throw new IllegalStateException("Gateway 启动配置快照已变化");
        var services = new java.util.TreeSet<String>();
        routes.definitions().forEach(route -> services.add(route.getUri().getHost()));
        services.add("identity-service");
        for (String service : services) runtime.watch(service);
        clients.verify();
      } catch (Exception failure) {
        throw new IllegalStateException("Gateway 路由依赖或独立内省身份检查失败");
      }
    };
  }

  private static ServerHttpSecurity stateless(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()));
  }

  @Bean
  @Order(-1)
  SecurityWebFilterChain gatewayAdminBrand(
      ServerHttpSecurity http,
      GatewayRoutes routes,
      org.springframework.beans.factory.ObjectProvider<
              com.ken.platform.gateway.bff.BffClientSettings>
          bff) {
    var errors = new GatewayErrors();
    boolean exposed =
        bff.getIfAvailable() != null
            || java.util.Arrays.asList(routes.publicPaths())
                .contains("/api/v1/identity/admin-config");
    stateless(http)
        .securityMatcher(
            org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
                .pathMatchers(HttpMethod.GET, "/api/v1/identity/admin-config"));
    http.authorizeExchange(
        requests -> {
          if (exposed) requests.anyExchange().permitAll();
          else requests.anyExchange().denyAll();
        });
    http.exceptionHandling(
        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    return http.build();
  }

  @Bean
  @Order(1)
  SecurityWebFilterChain gatewayBearer(
      ServerHttpSecurity http, GatewayRoutes routes, GatewayIntrospectionClients clients) {
    var errors = new GatewayErrors();
    stateless(http)
        .securityMatcher(
            org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
                .pathMatchers("/api/**"));
    // API 的认证依赖原生选路结果，在 GlobalFilter 中完成；这里不建立第二张路径路由表。
    http.authorizeExchange(requests -> requests.anyExchange().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityWebFilterChain gatewayPublic(
      ServerHttpSecurity http,
      GatewayRoutes routes,
      org.springframework.beans.factory.ObjectProvider<
              com.ken.platform.gateway.bff.BffClientSettings>
          bff) {
    var errors = new GatewayErrors();
    stateless(http);
    http.authorizeExchange(
        requests -> {
          requests
              .pathMatchers(
                  HttpMethod.GET,
                  "/actuator/health",
                  "/actuator/health/liveness",
                  "/actuator/health/readiness")
              .permitAll();
          if (routes.publicPaths().length > 0)
            requests.pathMatchers(routes.publicPaths()).permitAll();
          if (bff.getIfAvailable() != null)
            requests.pathMatchers(IdentityEndpoints.paths().toArray(String[]::new)).permitAll();
          requests.anyExchange().denyAll();
        });
    http.exceptionHandling(
        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    return http.build();
  }
}
