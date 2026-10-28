package com.ken.platform.foundation.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/** 仅独立管理监听上的 Prometheus 端点可由内网采集；应用端口不能借此匿名读取。 */
@AutoConfiguration
@ConditionalOnProperty(name = "MANAGEMENT_SERVER_PORT")
public class PlatformMetricsAutoConfiguration {
  static int metricsPort(Environment env) {
    int port = env.getRequiredProperty("MANAGEMENT_SERVER_PORT", Integer.class);
    int app =
        env.getProperty(
            "SERVICE_BIND_PORT",
            Integer.class,
            env.getProperty("server.port", Integer.class, 8080));
    if (port < 1 || port > 65535 || port == app)
      throw new IllegalArgumentException("采集端口必须独立于应用端口");
    return port;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class Servlet {
    @Bean
    @Order(-300)
    org.springframework.security.web.SecurityFilterChain metricsSecurity(
        org.springframework.security.config.annotation.web.builders.HttpSecurity http,
        Environment env)
        throws Exception {
      int port = metricsPort(env);
      http.securityMatcher(
          request ->
              request.getLocalPort() == port
                  && request.getRequestURI().equals("/actuator/prometheus"));
      http.authorizeHttpRequests(
          requests ->
              requests
                  .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/prometheus")
                  .permitAll()
                  .anyRequest()
                  .denyAll());
      return http.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class Reactive {
    @Bean
    @Order(-300)
    org.springframework.security.web.server.SecurityWebFilterChain metricsReactiveSecurity(
        org.springframework.security.config.web.server.ServerHttpSecurity http, Environment env) {
      int port = metricsPort(env);
      http.csrf(org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec::disable)
          .formLogin(
              org.springframework.security.config.web.server.ServerHttpSecurity.FormLoginSpec
                  ::disable)
          .httpBasic(
              org.springframework.security.config.web.server.ServerHttpSecurity.HttpBasicSpec
                  ::disable)
          .logout(
              org.springframework.security.config.web.server.ServerHttpSecurity.LogoutSpec::disable)
          .securityContextRepository(
              org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
                  .getInstance())
          .requestCache(
              cache ->
                  cache.requestCache(
                      org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
                          .getInstance()));
      http.securityMatcher(
          exchange -> {
            var request = exchange.getRequest();
            return request.getLocalAddress() != null
                    && request.getLocalAddress().getPort() == port
                    && request.getPath().value().equals("/actuator/prometheus")
                ? org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
                    .MatchResult.match()
                : org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
                    .MatchResult.notMatch();
          });
      http.authorizeExchange(
          requests ->
              requests
                  .pathMatchers(org.springframework.http.HttpMethod.GET, "/actuator/prometheus")
                  .permitAll()
                  .anyExchange()
                  .denyAll());
      return http.build();
    }
  }
}
