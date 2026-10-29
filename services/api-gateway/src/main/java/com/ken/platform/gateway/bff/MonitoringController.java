package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** 固定只读指标集合；公开 Bearer 与后台会话使用同一监控结果。 */
@RestController
public final class MonitoringController {
  private final ObjectProvider<BffTokens> tokens;
  private final BffIdentityClient identity;
  private final Environment environment;
  private final WebClient web;
  private static final List<String> QUERIES =
      List.of(
          "up{job=\"services\"}",
          "sum by(service,instance)(rate(http_server_requests_seconds_count[5m]) or rate(platform_http_request_duration_seconds_count[5m]))",
          "sum by(service,instance)(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]) or rate(platform_http_request_duration_seconds_count{status=~\"5..\"}[5m]))",
          "histogram_quantile(0.95,sum by(le,service,instance)(rate(http_server_requests_seconds_bucket[5m]) or rate(platform_http_request_duration_seconds_bucket[5m])))");

  public MonitoringController(
      ObjectProvider<BffTokens> tokens, BffIdentityClient identity, Environment environment) {
    this.tokens = tokens;
    this.identity = identity;
    this.environment = environment;
    this.web =
        WebClient.builder()
            .clientConnector(
                new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                    reactor.netty.http.client.HttpClient.create()
                        .followRedirect(false)
                        .disableRetry(true)
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                        .responseTimeout(Duration.ofSeconds(5))))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();
  }

  @GetMapping({
    "/api/v1/platform/monitoring/availability",
    "/bff/identity/platform/monitoring-summary/availability"
  })
  Mono<Map<String, Object>> availability(ServerWebExchange exchange) {
    return authorize(exchange).then(envelope(Map.of("enabled", enabled())));
  }

  @GetMapping({
    "/api/v1/platform/monitoring/services",
    "/bff/identity/platform/monitoring-summary/services"
  })
  Mono<Map<String, Object>> services(ServerWebExchange exchange) {
    return authorize(exchange)
        .then(
            Mono.defer(
                () -> {
                  if (!enabled())
                    return Mono.error(
                        new PlatformException("MONITORING_DISABLED", "本环境未启用监控", 404));
                  return Mono.zip(
                          query(QUERIES.get(0)),
                          query(QUERIES.get(1)),
                          query(QUERIES.get(2)),
                          query(QUERIES.get(3)))
                      .flatMap(
                          values -> {
                            var rates = values.getT2();
                            var failures = values.getT3();
                            var latency = values.getT4();
                            var rows = new ArrayList<Map<String, Object>>();
                            for (var item : values.getT1().entrySet()) {
                              var key = item.getKey();
                              var labels = key.split("\\|", 2);
                              var row = new LinkedHashMap<String, Object>();
                              row.put("service", labels[0]);
                              row.put("instance", labels[1]);
                              row.put("healthy", item.getValue() == 1);
                              Double rate = rates.get(key);
                              row.put("requests_per_second", rate);
                              row.put(
                                  "error_ratio",
                                  rate == null || rate <= 0
                                      ? null
                                      : failures.getOrDefault(key, 0d) / rate);
                              row.put("p95_seconds", latency.get(key));
                              rows.add(row);
                            }
                            return envelope(
                                Map.of(
                                    "items",
                                    rows,
                                    "sampled_at",
                                    java.time.Instant.now().toString(),
                                    "window_seconds",
                                    300));
                          });
                }));
  }

  private Mono<Void> authorize(ServerWebExchange exchange) {
    if (exchange.getRequest().getPath().value().startsWith("/bff/")) {
      if (tokens.getIfAvailable() == null) return Mono.error(denied());
      return exchange
          .getPrincipal()
          .ofType(OAuth2AuthenticationToken.class)
          .switchIfEmpty(Mono.error(denied()))
          .flatMap(
              auth -> {
                if (!"platform".equals(auth.getAuthorizedClientRegistrationId()))
                  return Mono.error(denied());
                return tokens
                    .getObject()
                    .current(exchange, auth)
                    .flatMap(
                        client ->
                            identity.application(
                                client.getAccessToken().getTokenValue(),
                                client.getClientRegistration()))
                    .flatMap(
                        principal ->
                            principal.monitoring() && principal.subject().equals(auth.getName())
                                ? Mono.<Void>empty()
                                : Mono.error(denied()));
              });
    }
    return new ServerBearerTokenAuthenticationConverter()
        .convert(exchange)
        .switchIfEmpty(
            Mono.error(new PlatformException("INVALID_CREDENTIALS", "需要 Bearer 访问令牌", 401)))
        .flatMap(auth -> identity.application((String) auth.getCredentials()))
        .flatMap(principal -> principal.monitoring() ? Mono.<Void>empty() : Mono.error(denied()))
        .then()
        .onErrorMap(
            org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
            failure -> new PlatformException("INVALID_CREDENTIALS", "Bearer 访问令牌格式无效", 401));
  }

  private Mono<Map<String, Double>> query(String expression) {
    return web.get()
        .uri(
            environment.getProperty("GRAFANA_INTERNAL_ORIGIN", "http://grafana:3000")
                + "/bff/identity/platform/monitoring/api/datasources/proxy/uid/prometheus/api/v1/query?query={query}",
            expression)
        .header("X-WEBAUTH-USER", "platform-monitoring")
        .header("X-WEBAUTH-ROLE", "Viewer")
        .retrieve()
        .bodyToMono(JsonNode.class)
        .timeout(Duration.ofSeconds(5))
        .map(
            root -> {
              if (!"success".equals(root.path("status").asString())) throw unavailable();
              var result = new TreeMap<String, Double>();
              for (var value : root.path("data").path("result")) {
                var metric = value.path("metric");
                double number = Double.parseDouble(value.path("value").get(1).asString());
                if (Double.isFinite(number))
                  result.put(
                      metric.path("service").asString() + "|" + metric.path("instance").asString(),
                      number);
              }
              return (Map<String, Double>) result;
            })
        .onErrorMap(error -> error instanceof PlatformException ? error : unavailable());
  }

  private Mono<Map<String, Object>> envelope(Object data) {
    return Mono.deferContextual(
        context -> {
          CallContext call = context.getOrDefault(CallContext.class, null);
          return Mono.just(
              Map.of(
                  "code",
                  "OK",
                  "message",
                  "成功",
                  "data",
                  data,
                  "request_id",
                  call == null ? "" : call.requestId()));
        });
  }

  private boolean enabled() {
    return Arrays.asList(environment.getProperty("COMPOSE_PROFILES", "").split(","))
        .contains("monitoring");
  }

  private static PlatformException denied() {
    return new PlatformException("FORBIDDEN", "没有服务监控权限", 403);
  }

  private static PlatformException unavailable() {
    return new PlatformException("MONITORING_UNAVAILABLE", "监控采集暂不可用", 503);
  }
}
