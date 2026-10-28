package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

/** 只为 local 的已登记 Swagger 来源开放 token POST，不开放 Cookie 或其他协议端点。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "APP_ENV", havingValue = "local")
public class LocalSwaggerCorsConfiguration {
  @Bean
  FilterRegistrationBean<CorsFilter> localSwaggerCors(ClientStore store) {
    var registration =
        new FilterRegistrationBean<>(
            new CorsFilter(
                request -> {
                  String origin = request.getHeader("Origin");
                  if (origin == null) return null;
                  var allowed =
                      store.localSwaggerRedirects().stream()
                          .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                          .map(URI::create)
                          .filter(
                              uri ->
                                  "http".equals(uri.getScheme())
                                      && "localhost".equals(uri.getHost())
                                      && uri.getPort() > 0
                                      && uri.getPort() <= 65535
                                      && uri.getUserInfo() == null)
                          .map(uri -> "http://localhost:" + uri.getPort())
                          .toList();
                  if (!allowed.contains(origin)) return new CorsConfiguration();
                  var cors = new CorsConfiguration();
                  cors.setAllowedOrigins(List.of(origin));
                  cors.setAllowedMethods(List.of("POST", "OPTIONS"));
                  cors.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
                  cors.setAllowCredentials(false);
                  cors.setMaxAge(600L);
                  return cors;
                }));
    registration.setUrlPatterns(List.of("/oauth2/token"));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
