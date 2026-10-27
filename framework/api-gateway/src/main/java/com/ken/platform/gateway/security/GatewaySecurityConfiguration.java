package com.ken.platform.gateway.security;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.CallContext;
import java.time.Clock;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenReactiveAuthenticationManager;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;

/** 显式路由与独立 audience 的异步 opaque 入口；BFF 在独立命名空间接续。 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfiguration {
  @Bean
  GatewayRoutes platformGatewayRoutes(RuntimeParameters parameters, GatewayProperties properties) {
    if (!properties.getRoutes().isEmpty())
      throw new IllegalArgumentException("Gateway 路由只能来自已校验的 api-gateway.yaml，不能另设 Spring 路由");
    return new GatewayRoutes(parameters);
  }

  @Bean(destroyMethod = "close")
  GatewayIntrospectionClients gatewayIntrospectionClients(
      Environment environment,
      RuntimeParameters parameters,
      ServiceDiscovery discovery,
      Clock clock) {
    return new GatewayIntrospectionClients(environment, parameters, discovery, clock);
  }

  @Bean
  RouteLocator platformExplicitRoutes(
      RouteLocatorBuilder builder, RuntimeParameters parameters, GatewayRoutes validated) {
    var routes = builder.routes();
    for (var route : parameters.gatewayRoutes())
      routes.route(
          route.id(), predicate -> predicate.path(route.path()).uri("lb://" + route.service()));
    return routes.build();
  }

  @Bean
  PlatformReadinessCheck gatewayDependencies(
      NacosRuntime runtime, RuntimeParameters parameters, GatewayIntrospectionClients clients) {
    return () -> {
      try {
        if (!parameters.sameRestartFields(runtime.parameters()))
          throw new IllegalStateException("Gateway 启动配置快照已变化");
        var services = new java.util.TreeSet<String>();
        parameters.gatewayRoutes().forEach(route -> services.add(route.service()));
        if (!clients.empty()) services.add("identity-service");
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
    http.authorizeExchange(requests -> requests.anyExchange().authenticated());
    http.exceptionHandling(
        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    var authentication =
        new org.springframework.security.web.server.authentication.AuthenticationWebFilter(
            (org.springframework.security.authentication.ReactiveAuthenticationManagerResolver<
                    org.springframework.web.server.ServerWebExchange>)
                exchange -> {
                  var route = routes.api(exchange.getRequest().getPath().pathWithinApplication());
                  if (route == null)
                    return Mono.error(new InvalidBearerTokenException("未登记的 API 入口"));
                  var introspector = clients.forAudience(route.audience());
                  return Mono.just(
                      new OpaqueTokenReactiveAuthenticationManager(
                          token ->
                              Mono.deferContextual(
                                  context ->
                                      Mono.fromFuture(
                                              introspector.introspectAsync(
                                                  token,
                                                  context.getOrDefault(CallContext.class, null)))
                                          .cast(OAuth2AuthenticatedPrincipal.class))));
                });
    authentication.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
    // 官方默认失败处理器会重抛 AuthenticationServiceException；显式保留 503 合同。
    authentication.setAuthenticationFailureHandler(
        (exchange, failure) -> errors.commence(exchange.getExchange(), failure));
    authentication.setServerAuthenticationConverter(
        exchange -> {
          var values = exchange.getRequest().getHeaders().get("Authorization");
          if (values == null || values.isEmpty()) return Mono.empty();
          if (values.size() != 1
              || values.getFirst().length() > 8199
              || !values.getFirst().matches("(?i)Bearer [A-Za-z0-9._~+/-]+=*"))
            return Mono.error(new InvalidBearerTokenException("访问令牌无效"));
          return Mono.just(new BearerTokenAuthenticationToken(values.getFirst().substring(7)));
        });
    http.addFilterAt(
        authentication,
        org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION);
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
            requests
                .pathMatchers(
                    com.ken.platform.foundation.config.GatewayRouteSpec.identityPaths()
                        .toArray(String[]::new))
                .permitAll();
          requests.anyExchange().denyAll();
        });
    http.exceptionHandling(
        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    return http.build();
  }
}
