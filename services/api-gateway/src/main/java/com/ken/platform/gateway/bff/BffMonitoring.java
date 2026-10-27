package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.http.PlatformException;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 固定内网 Grafana 代理；每个资源请求实时核对平台身份和权限。 */
@RestController
@ConditionalOnProperty(name = "GATEWAY_BFF_CLIENTS_SECRET_FILE")
public final class BffMonitoring {
  static final String PREFIX = "/bff/identity/platform/monitoring/";
  private final BffTokens tokens;
  private final BffIdentityClient identity;
  private final Environment environment;
  private final WebClient web =
      WebClient.builder()
          .clientConnector(
              new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                  reactor.netty.http.client.HttpClient.create()
                      .followRedirect(false)
                      .disableRetry(true)
                      .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                      .responseTimeout(Duration.ofSeconds(10))))
          .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
          .build();

  public BffMonitoring(BffTokens tokens, BffIdentityClient identity, Environment environment) {
    this.tokens = tokens;
    this.identity = identity;
    this.environment = environment;
  }

  static void checkRequest(ServerWebExchange exchange, String origin) {
    String path = exchange.getRequest().getURI().getRawPath();
    if (!path.startsWith(PREFIX)
        || path.contains("%")
        || path.contains("..")
        || path.contains("\\")) throw denied();
    var method = exchange.getRequest().getMethod();
    if (method == HttpMethod.GET || method == HttpMethod.HEAD) return;
    if (method != HttpMethod.POST || !path.equals(PREFIX + "api/ds/query")) throw denied();
    var headers = exchange.getRequest().getHeaders();
    if (!origin.equals(headers.getOrigin())) throw denied();
    String site = headers.getFirst("Sec-Fetch-Site");
    if (site != null && !site.equals("same-origin")) throw denied();
    if (headers.getContentType() == null
        || !headers
            .getContentType()
            .isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON)) throw denied();
  }

  @org.springframework.web.bind.annotation.GetMapping(
      "/bff/identity/platform/monitoring-availability")
  Mono<java.util.Map<String, Boolean>> availability(ServerWebExchange exchange) {
    return exchange
        .getPrincipal()
        .ofType(OAuth2AuthenticationToken.class)
        .switchIfEmpty(Mono.error(denied()))
        .flatMap(
            auth -> {
              if (!"platform".equals(auth.getAuthorizedClientRegistrationId()))
                return Mono.error(denied());
              return tokens
                  .current(exchange, auth)
                  .flatMap(
                      client ->
                          identity.application(
                              client.getAccessToken().getTokenValue(),
                              client.getClientRegistration()))
                  .map(
                      current -> {
                        if (!current.monitoring() || !current.subject().equals(auth.getName()))
                          throw denied();
                        return java.util.Map.of("enabled", enabled());
                      });
            });
  }

  @RequestMapping(PREFIX + "{*path}")
  Mono<Void> relay(ServerWebExchange exchange) {
    if (!enabled())
      return Mono.error(new PlatformException("MONITORING_DISABLED", "本环境未启用监控", 404));
    checkRequest(exchange, environment.getRequiredProperty("PUBLIC_ORIGIN"));
    return exchange
        .getPrincipal()
        .ofType(OAuth2AuthenticationToken.class)
        .switchIfEmpty(Mono.error(denied()))
        .flatMap(
            auth -> {
              if (!"platform".equals(auth.getAuthorizedClientRegistrationId()))
                return Mono.error(denied());
              return tokens
                  .current(exchange, auth)
                  .flatMap(
                      client ->
                          identity.application(
                              client.getAccessToken().getTokenValue(),
                              client.getClientRegistration()))
                  .flatMap(
                      current -> {
                        if (!current.monitoring() || !current.subject().equals(auth.getName()))
                          return Mono.error(denied());
                        return forward(exchange, current.subject());
                      });
            });
  }

  private Mono<Void> forward(ServerWebExchange exchange, String subject) {
    String query = exchange.getRequest().getURI().getRawQuery();
    URI uri =
        URI.create(
            environment.getProperty("GRAFANA_INTERNAL_ORIGIN", "http://grafana:3000")
                + exchange.getRequest().getURI().getRawPath()
                + (query == null ? "" : "?" + query));
    var request =
        web.method(exchange.getRequest().getMethod())
            .uri(uri)
            .header("X-WEBAUTH-USER", subject)
            .header("X-WEBAUTH-ROLE", "Viewer");
    var contentType = exchange.getRequest().getHeaders().getContentType();
    if (contentType != null) request.contentType(contentType);
    return org.springframework.core.io.buffer.DataBufferUtils.join(
            exchange.getRequest().getBody(), 1024 * 1024)
        .map(
            buffer -> {
              byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
              return bytes;
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            body ->
                request
                    .bodyValue(body)
                    .exchangeToMono(
                        response -> {
                          var output = exchange.getResponse();
                          if (response.statusCode().is3xxRedirection())
                            return response
                                .releaseBody()
                                .then(
                                    Mono.error(
                                        new PlatformException(
                                            "MONITORING_UNAVAILABLE", "监控返回了非预期跳转", 503)));
                          output.setStatusCode(response.statusCode());
                          var type = response.headers().contentType();
                          type.ifPresent(output.getHeaders()::setContentType);
                          output.beforeCommit(
                              () -> {
                                output.getHeaders().set("X-Frame-Options", "SAMEORIGIN");
                                output
                                    .getHeaders()
                                    .set(
                                        "Content-Security-Policy",
                                        response
                                            .headers()
                                            .header("Content-Security-Policy")
                                            .stream()
                                            .findFirst()
                                            .orElse("default-src 'none'; frame-ancestors 'self'"));
                                output.getHeaders().setCacheControl("no-store");
                                output.getHeaders().remove("Set-Cookie");
                                return Mono.empty();
                              });
                          return response
                              .bodyToMono(byte[].class)
                              .defaultIfEmpty(new byte[0])
                              .flatMap(
                                  bytes ->
                                      output.writeWith(
                                          Mono.just(output.bufferFactory().wrap(bytes))));
                        }))
        .timeout(Duration.ofSeconds(10))
        .onErrorMap(
            error ->
                error instanceof PlatformException
                    ? error
                    : new PlatformException("MONITORING_UNAVAILABLE", "监控暂不可用", 503));
  }

  private boolean enabled() {
    return java.util.Arrays.asList(environment.getProperty("COMPOSE_PROFILES", "").split(","))
        .contains("monitoring");
  }

  private static PlatformException denied() {
    return new PlatformException("FORBIDDEN", "没有服务监控权限或请求来源不合法", 403);
  }
}
