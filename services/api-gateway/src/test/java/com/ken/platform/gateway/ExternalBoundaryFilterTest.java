package com.ken.platform.gateway;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.web.ReactiveRequestContextFilter;
import com.ken.platform.gateway.config.ExternalBoundaryFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/** 真 Reactor Netty HTTP 边界；不将此过滤器测试当作完整网关认证验收。 */
class ExternalBoundaryFilterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> FORGED =
      List.of(
          "login-user",
          "Remote-User",
          "X-Remote-User",
          "X-User-Id",
          "X-Tenant-Id",
          "X-Principal-Id",
          "X-Principal-Type",
          "X-Client-Id",
          "X-Roles",
          "X-Permissions",
          "X-Data-Scopes",
          "X-Service-Authorization",
          "X-Forwarded-User",
          "X-Forwarded-Tenant",
          "X-Auth-User");
  private final AtomicInteger forwarded = new AtomicInteger();
  private final List<String> captured = new CopyOnWriteArrayList<>();
  private final HttpClient client = HttpClient.newHttpClient();
  private DisposableServer server;
  private String origin;

  @BeforeEach
  void start() {
    var handler =
        WebHttpHandlerBuilder.webHandler(
                exchange ->
                    Mono.deferContextual(
                        context -> {
                          forwarded.incrementAndGet();
                          var headers = exchange.getRequest().getHeaders();
                          var request = context.get(CallContext.class);
                          captured.add(request.requestId());
                          var body =
                              Map.of(
                                  "stripped",
                                  FORGED.stream().noneMatch(headers::containsHeader),
                                  "bearer_preserved",
                                  "Bearer external-fixture"
                                      .equals(headers.getFirst("Authorization")),
                                  "cookie_preserved",
                                  "BFF=fixture".equals(headers.getFirst("Cookie")),
                                  "request_id",
                                  request.requestId(),
                                  "attribute_context_matches",
                                  request.equals(
                                      exchange.getAttribute(CallContext.class.getName())),
                                  "event_loop",
                                  Schedulers.isInNonBlockingThread());
                          exchange
                              .getResponse()
                              .getHeaders()
                              .setContentType(MediaType.APPLICATION_JSON);
                          return exchange
                              .getResponse()
                              .writeWith(
                                  Mono.just(
                                      exchange
                                          .getResponse()
                                          .bufferFactory()
                                          .wrap(JSON.writeValueAsBytes(body))));
                        }))
            .filter(
                new ReactiveRequestContextFilter(Clock.systemUTC(), RuntimeParameters.defaults()))
            .filter(new ExternalBoundaryFilter())
            .build();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow();
    origin = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void stop() {
    if (server != null) server.disposeNow();
    client.shutdownNow();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void stripsAllExternalIdentityValuesWithoutDestroyingBearerCookieAndRequestContext()
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(origin + "/api/v1/documents/fixture"))
            .header("Authorization", "Bearer external-fixture")
            .header("Cookie", "BFF=fixture")
            .header("X-Request-Id", "gateway-boundary-1");
    FORGED.forEach(name -> request.header(name, "forged"));
    request.header("x-user-id", "second-forged");
    var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var value = JSON.readTree(response.body());
    for (String flag :
        List.of(
            "stripped",
            "bearer_preserved",
            "cookie_preserved",
            "attribute_context_matches",
            "event_loop")) assertThat(value.get(flag).asBoolean()).as(flag).isTrue();
    assertThat(value.get("request_id").asString()).isEqualTo("gateway-boundary-1");
    assertThat(response.headers().firstValue("X-Request-Id")).contains("gateway-boundary-1");
  }

  @Test
  void rejectsDecodedInternalEndpointsBeforeAnyDownstreamInvocation() throws Exception {
    for (String path :
        List.of("/internal", "/internal/v1/jobs", "/in%74ernal/v1/jobs", "/internal%2fv1/jobs")) {
      var response = get(path);
      assertThat(response.statusCode()).as(path).isEqualTo(403);
      assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("FORBIDDEN");
      assertThat(JSON.readTree(response.body()).get("request_id").asString())
          .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }
    assertThat(forwarded.get()).isZero();
    assertThat(get("/api/v1/internal-documents").statusCode()).isEqualTo(200);
    assertThat(get("/internal-looking").statusCode()).isEqualTo(200);
  }

  @Test
  void ambiguousPathsCannotBeReinterpretedAsPrivilegedEndpoints() throws Exception {
    for (String path :
        List.of(
            "/api/../internal/v1/jobs",
            "/internal;anything/v1/jobs",
            "/api//v1/documents",
            "/api/%252e%252e/internal",
            "/api%5cinternal")) assertThat(get(path).statusCode()).as(path).isEqualTo(400);
    assertThat(forwarded.get()).isZero();
  }

  @Test
  void retiredTenantConsoleNeverReachesAuthenticationOrDownstream() throws Exception {
    for (String path :
        List.of("", "/csrf", "/login", "/callback", "/api/me", "/api/users", "/logout")) {
      for (String method : List.of("GET", "POST")) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(origin + "/bff/identity/tenant" + path))
                    .header("Cookie", "BFF=old-tenant-session")
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + path).isEqualTo(404);
      }
    }
    assertThat(get("/bff/identity/%74enant/api/me").statusCode()).isEqualTo(404);
    assertThat(forwarded.get()).isZero();
    for (String path :
        List.of(
            "/bff/identity/platform/csrf",
            "/bff/web/login",
            "/api/v1/identity/users",
            "/identity/login?realm=TENANT"))
      assertThat(get(path).statusCode()).as(path).isEqualTo(200);
    assertThat(forwarded.get()).isEqualTo(4);
  }

  @Test
  void simultaneousRequestsKeepTheirOwnReactiveContextOnEventLoop() {
    var futures =
        java.util.stream.IntStream.range(0, 12)
            .mapToObj(
                number ->
                    client
                        .sendAsync(
                            HttpRequest.newBuilder(URI.create(origin + "/api/v1/documents/fixture"))
                                .header("X-Request-Id", "concurrent-" + number)
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString())
                        .thenAccept(
                            response -> {
                              assertThat(response.statusCode()).isEqualTo(200);
                              assertThat(
                                      JSON.readTree(response.body()).get("request_id").asString())
                                  .isEqualTo("concurrent-" + number);
                              assertThat(
                                      JSON.readTree(response.body()).get("event_loop").asBoolean())
                                  .isTrue();
                            }))
            .toList();
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    assertThat(captured).hasSize(12).doesNotHaveDuplicates();
  }
}
