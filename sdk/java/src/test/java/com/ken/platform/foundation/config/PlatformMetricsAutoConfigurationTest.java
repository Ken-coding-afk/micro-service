package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlatformMetricsAutoConfigurationTest {
  @Test
  void collectorMustUseAnIndependentValidPort() {
    var env = new MockEnvironment().withProperty("SERVICE_BIND_PORT", "8081");
    for (String invalid : new String[] {"0", "65536", "8081"}) {
      env.setProperty("MANAGEMENT_SERVER_PORT", invalid);
      assertThatThrownBy(() -> PlatformMetricsAutoConfiguration.metricsPort(env))
          .isInstanceOf(IllegalArgumentException.class);
    }
    env.setProperty("MANAGEMENT_SERVER_PORT", "9081");
    assertThat(PlatformMetricsAutoConfiguration.metricsPort(env)).isEqualTo(9081);
  }

  @Test
  void reactiveCollectorNeverReadsBrowserSessionsOrExposesTheApplicationPort() {
    var env =
        new MockEnvironment()
            .withProperty("SERVICE_BIND_PORT", "8080")
            .withProperty("MANAGEMENT_SERVER_PORT", "9080");
    var metrics =
        new PlatformMetricsAutoConfiguration.Reactive()
            .metricsReactiveSecurity(
                org.springframework.security.config.web.server.ServerHttpSecurity.http(), env);
    var fallback = org.springframework.security.config.web.server.ServerHttpSecurity.http();
    fallback
        .csrf(org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec::disable)
        .securityContextRepository(
            org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
                .getInstance())
        .requestCache(
            cache ->
                cache.requestCache(
                    org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
                        .getInstance()))
        .authorizeExchange(requests -> requests.anyExchange().denyAll());
    var sessionReads = new java.util.concurrent.atomic.AtomicInteger();
    var handler =
        org.springframework.web.server.adapter.WebHttpHandlerBuilder.webHandler(
                exchange ->
                    exchange
                        .getResponse()
                        .writeWith(
                            reactor.core.publisher.Mono.just(
                                exchange
                                    .getResponse()
                                    .bufferFactory()
                                    .wrap("metric 1".getBytes()))))
            .sessionManager(
                exchange ->
                    reactor.core.publisher.Mono.defer(
                        () -> {
                          sessionReads.incrementAndGet();
                          return reactor.core.publisher.Mono.error(
                              new IllegalStateException("非 BFF 入口不得读取浏览器会话"));
                        }))
            .filter(
                new org.springframework.security.web.server.WebFilterChainProxy(
                    java.util.List.of(metrics, fallback.build())))
            .build();
    for (int port : new int[] {9080, 8080}) {
      for (var method :
          new org.springframework.http.HttpMethod[] {
            org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.POST
          }) {
        var request =
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.method(
                    method, "http://localhost:" + port + "/actuator/prometheus")
                .localAddress(new java.net.InetSocketAddress("127.0.0.1", port))
                .header("Cookie", "BFF_PLATFORM=untrusted")
                .build();
        var response = new org.springframework.mock.http.server.reactive.MockServerHttpResponse();
        handler.handle(request, response).block(java.time.Duration.ofSeconds(5));
        if (port == 9080 && method == org.springframework.http.HttpMethod.GET) {
          assertThat(response.getStatusCode()).isNull();
          assertThat(response.getBodyAsString().block()).isEqualTo("metric 1");
        } else {
          assertThat(response.getStatusCode())
              .isIn(
                  org.springframework.http.HttpStatus.UNAUTHORIZED,
                  org.springframework.http.HttpStatus.FORBIDDEN);
        }
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getHeaders().getFirst("Location")).isNull();
      }
    }
    assertThat(sessionReads).hasValue(0);
  }
}
