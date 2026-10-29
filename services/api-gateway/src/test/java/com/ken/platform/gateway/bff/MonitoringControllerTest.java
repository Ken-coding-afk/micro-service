package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class MonitoringControllerTest {
  private MonitoringController controller(
      MockEnvironment env, String type, Set<String> permissions) {
    var identity = mock(BffIdentityClient.class);
    when(identity.application("valid-token"))
        .thenReturn(
            reactor.core.publisher.Mono.just(
                new BffIdentityClient.Identity(
                    UUID.randomUUID().toString(),
                    type,
                    type.equals("user") ? UUID.randomUUID() : null,
                    "external-platform-app",
                    Set.of("identity-service"),
                    type.equals("platform") && permissions.contains("platform:monitoring:read"))));
    var beans = new StaticListableBeanFactory();
    return new MonitoringController(beans.getBeanProvider(BffTokens.class), identity, env);
  }

  private MockServerWebExchange exchange() {
    return MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/platform/monitoring/services")
            .header("Authorization", "Bearer valid-token"));
  }

  @Test
  void disabledIsExplicitAndStillRequiresPlatformPermission() {
    var valid = controller(new MockEnvironment(), "platform", Set.of("platform:monitoring:read"));
    assertThat(valid.availability(exchange()).block())
        .containsEntry("data", Map.of("enabled", false));
    assertThatThrownBy(() -> valid.services(exchange()).block()).hasMessage("本环境未启用监控");
    assertThatThrownBy(
            () ->
                controller(new MockEnvironment(), "user", Set.of("platform:monitoring:read"))
                    .availability(exchange())
                    .block())
        .isInstanceOf(com.ken.platform.foundation.http.PlatformException.class);
    assertThatThrownBy(
            () ->
                controller(new MockEnvironment(), "platform", Set.of())
                    .availability(exchange())
                    .block())
        .isInstanceOf(com.ken.platform.foundation.http.PlatformException.class);
    assertThatThrownBy(
            () ->
                valid
                    .availability(
                        MockServerWebExchange.from(
                            MockServerHttpRequest.get("/api/v1/platform/monitoring/availability")))
                    .block())
        .hasMessage("需要 Bearer 访问令牌");
  }

  @Test
  void fixedQueriesUseRealHttpAndDistinguishMissingLatencyFromHealthyTarget() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var queries = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    server.createContext(
        "/bff/identity/platform/monitoring/api/datasources/proxy/uid/prometheus/api/v1/query",
        request -> {
          String query =
              java.net.URLDecoder.decode(
                  request.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          queries.add(query);
          assertThat(request.getRequestHeaders().getFirst("X-WEBAUTH-ROLE")).isEqualTo("Viewer");
          String value =
              query.contains("up{")
                  ? "1"
                  : query.contains("histogram_quantile")
                      ? "NaN"
                      : query.contains("5..") ? "0.2" : "10";
          byte[] body =
              ("{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"service\":\"orders\",\"instance\":\"orders:8080\"},\"value\":[1,\""
                      + value
                      + "\"]}]}}")
                  .getBytes(StandardCharsets.UTF_8);
          request.getResponseHeaders().set("Content-Type", "application/json");
          request.sendResponseHeaders(200, body.length);
          request.getResponseBody().write(body);
          request.close();
        });
    server.start();
    try {
      var env =
          new MockEnvironment()
              .withProperty("COMPOSE_PROFILES", "monitoring")
              .withProperty(
                  "GRAFANA_INTERNAL_ORIGIN", "http://127.0.0.1:" + server.getAddress().getPort());
      var result =
          controller(env, "platform", Set.of("platform:monitoring:read"))
              .services(exchange())
              .block();
      assertThat(queries).hasSize(4);
      var data = (Map<?, ?>) result.get("data");
      var item = (Map<?, ?>) ((List<?>) data.get("items")).getFirst();
      assertThat(item.get("healthy")).isEqualTo(true);
      assertThat(item.get("error_ratio")).isEqualTo(0.02);
      assertThat(item.get("p95_seconds")).isNull();
    } finally {
      server.stop(0);
    }
  }
}
