package com.ken.platform.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.config.PlatformAutoConfiguration;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.web.ReactiveRequestContextFilter;
import com.ken.platform.gateway.config.ExternalBoundaryFilter;
import com.ken.platform.gateway.security.GatewayPrincipalContextFilter;
import com.ken.platform.gateway.security.GatewaySecurityConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import tools.jackson.databind.json.JsonMapper;

/** 真 Netty + 官方 Security 链/异步 SDK；内省 HTTP stub 不替代真实 identity 联调。 */
class GatewaySecurityTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String USER = "11111111-1111-4111-8111-111111111111",
      TENANT = "22222222-2222-4222-8222-222222222222";
  @TempDir Path directory;
  private HttpServer identity;
  private DisposableServer gateway;
  private AnnotationConfigApplicationContext application;
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> readers = new CopyOnWriteArrayList<>();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final CountDownLatch slowEntered = new CountDownLatch(1),
      slowRelease = new CountDownLatch(1);
  private reactor.netty.resources.LoopResources loops;
  private String origin;

  @Configuration(proxyBeanMethods = false)
  @EnableWebFluxSecurity
  @Import({PlatformAutoConfiguration.class, GatewaySecurityConfiguration.class})
  static class ConfigurationFixture {
    @Bean
    GatewayProperties properties(RuntimeParameters parameters) {
      return RouteFixtures.properties(parameters);
    }

    @Bean
    org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory pathPredicate() {
      return new org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory(
          new org.springframework.boot.webflux.autoconfigure.WebFluxProperties());
    }

    @Bean
    RouteLocatorBuilder routeBuilder(ConfigurableApplicationContext context) {
      return new RouteLocatorBuilder(context);
    }

    @Bean
    NacosRuntime runtime(RuntimeParameters parameters) {
      var runtime = mock(NacosRuntime.class);
      when(runtime.parameters()).thenReturn(parameters);
      return runtime;
    }

    @Bean
    ReactiveRequestContextFilter requestContext(Clock clock, RuntimeParameters parameters) {
      return new ReactiveRequestContextFilter(clock, parameters);
    }

    @Bean
    ExternalBoundaryFilter boundary() {
      return new ExternalBoundaryFilter();
    }

    @Bean
    com.ken.platform.gateway.security.GatewayForwardedHeaderFilter forwarded(
        org.springframework.core.env.Environment environment) {
      return new com.ken.platform.gateway.security.GatewayForwardedHeaderFilter(environment);
    }

    @Bean
    GatewayPrincipalContextFilter principal() {
      return new GatewayPrincipalContextFilter();
    }

    @Bean
    WebHandler webHandler(
        com.ken.platform.gateway.security.GatewayRoutes routes,
        com.ken.platform.gateway.security.GatewayAuthenticationFilter auth,
        GatewayPrincipalContextFilter principal) {
      WebHandler terminal =
          exchange ->
              Mono.deferContextual(
                  reactor -> {
                    var context = reactor.get(CallContext.class);
                    var headers = exchange.getRequest().getHeaders();
                    var body = new HashMap<String, Object>();
                    body.put("subject", context.principalId());
                    body.put("tenant", context.tenantId());
                    body.put("request_id", context.requestId());
                    body.put(
                        "metadata_forwarded",
                        context.requestId().equals(headers.getFirst("X-Request-Id"))
                            && String.valueOf(context.deadline().toEpochMilli())
                                .equals(headers.getFirst("X-Request-Deadline"))
                            && java.util.Objects.equals(
                                context.traceparent(), headers.getFirst("traceparent")));
                    body.put("token_preserved", "user-token".equals(context.accessToken()));
                    body.put(
                        "forged_headers_absent",
                        !headers.containsHeader("X-User-Id")
                            && !headers.containsHeader("X-Tenant-Id")
                            && !headers.containsHeader("X-Service-Authorization"));
                    body.put(
                        "protocol_basic_preserved",
                        "Basic fixture".equals(headers.getFirst("Authorization")));
                    body.put(
                        "sso_cookie_preserved",
                        "IDENTITY_SSO=fixture".equals(headers.getFirst("Cookie")));
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return exchange
                        .getResponse()
                        .writeWith(
                            Mono.just(
                                exchange
                                    .getResponse()
                                    .bufferFactory()
                                    .wrap(JSON.writeValueAsBytes(body))));
                  });
      var builder =
          new RouteLocatorBuilder(
              new org.springframework.context.support.GenericApplicationContext());
      return exchange -> {
        if (exchange.getRequest().getPath().value().startsWith("/actuator/health"))
          return terminal.handle(exchange);
        var all = new java.util.ArrayList<>(routes.identityRoutes());
        all.addAll(routes.definitions());
        var definition =
            all.stream()
                .filter(
                    d ->
                        org.springframework.web.util.pattern.PathPatternParser.defaultInstance
                            .parse(
                                d.getPredicates().getFirst().getArgs().values().iterator().next())
                            .matches(exchange.getRequest().getPath().pathWithinApplication()))
                .findFirst();
        if (definition.isEmpty()) {
          exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND);
          return exchange.getResponse().setComplete();
        }
        var d = definition.get();
        var route =
            org.springframework.cloud.gateway.route.Route.async(d)
                .asyncPredicate(e -> Mono.just(true))
                .build();
        exchange
            .getAttributes()
            .put(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                route);
        return auth.filter(exchange, e -> principal.filter(e, terminal::handle));
      };
    }
  }

  private RuntimeParameters parameters() {
    return RouteFixtures.parameters(
        RouteFixtures.route("documents", "/api/v1/documents/**", "document-service", "documents"),
        RouteFixtures.route(
            "analysis", "/api/v1/analysis-jobs/**", "analysis-service", "analysis"));
  }

  @BeforeEach
  void start(org.junit.jupiter.api.TestInfo info) throws Exception {
    identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    identity.createContext(
        "/oauth2/introspect",
        exchange -> {
          String basic = exchange.getRequestHeaders().getFirst("Authorization");
          String credentials =
              new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8);
          readers.add(credentials);
          String audience =
              credentials.equals("gateway-doc:s-doc")
                  ? "documents"
                  : credentials.equals("gateway-analysis:s-analysis") ? "analysis" : null;
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String token = URLDecoder.decode(form.substring(6), StandardCharsets.UTF_8);
          if (token.equals("slow-token")) {
            slowEntered.countDown();
            try {
              if (!slowRelease.await(8, TimeUnit.SECONDS)) throw new java.io.IOException("夹具等待超时");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("夹具等待中断");
            }
          }
          Map<String, Object> claims;
          if (audience == null
              || !active.get()
              || token.startsWith("startup-")
              || token.equals("invalid")) claims = Map.of("active", false);
          else {
            claims = new HashMap<>();
            claims.put("active", true);
            claims.put("iss", "http://localhost:18080");
            claims.put("aud", List.of(token.equals("wrong-audience") ? "elsewhere" : audience));
            claims.put(
                "sub", token.equals("user-second") ? "33333333-3333-4333-8333-333333333333" : USER);
            claims.put(
                "tenant_id",
                token.equals("user-second") ? "44444444-4444-4444-8444-444444444444" : TENANT);
            claims.put("client_id", "web-fixture");
            claims.put("principal_type", "user");
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
            claims.put("scope", "documents.read");
            claims.put("permissions", List.of("document:read"));
            claims.put("data_scopes", Map.of("document:read", "SELF"));
          }
          byte[] body = JSON.writeValueAsBytes(claims);
          exchange.sendResponseHeaders(token.equals("unavailable") ? 503 : 200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    identity.start();
    var records = new ArrayList<Map<String, String>>();
    for (String audience : List.of("documents", "analysis")) {
      String tag = audience.equals("documents") ? "doc" : "analysis";
      Path secret = directory.resolve(tag + ".password");
      Files.writeString(secret, "s-" + tag);
      records.add(
          Map.of(
              "audience",
              audience,
              "client_id",
              "gateway-" + tag,
              "secret_file",
              secret.toString()));
    }
    Path binding = directory.resolve("readers.json");
    Files.writeString(binding, JSON.writeValueAsString(Map.of("readers", records)));
    application = new AnnotationConfigApplicationContext();
    application
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "fixture-bindings",
                Map.of(
                    "APP_ENV",
                    "local",
                    "PUBLIC_ORIGIN",
                    "http://localhost:18080",
                    "GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE",
                    binding.toString())));
    application.registerBean("platformParameters", RuntimeParameters.class, this::parameters);
    if (info.getTestMethod()
        .orElseThrow()
        .getName()
        .equals("browserConsentUsesExactIdentityRouteWithoutGatewaySession")) {
      application.registerBean(
          com.ken.platform.gateway.bff.BffClientSettings.class,
          () -> mock(com.ken.platform.gateway.bff.BffClientSettings.class));
      application.registerBean(
          "webSessionManager",
          org.springframework.web.server.session.WebSessionManager.class,
          () -> exchange -> Mono.error(new IllegalStateException("身份页面不能读取网关会话")));
    }
    application.register(ConfigurationFixture.class);
    application.refresh();
    application
        .getBean(ServiceDiscovery.class)
        .confirm(
            "identity-service",
            List.of(new ServiceInstance("127.0.0.1", identity.getAddress().getPort(), "http")));
    loops = reactor.netty.resources.LoopResources.create("gateway-security-fixture", 1, true);
    gateway =
        reactor.netty.http.server.HttpServer.create()
            .runOn(loops)
            .host("127.0.0.1")
            .port(0)
            .handle(
                new ReactorHttpHandlerAdapter(
                    WebHttpHandlerBuilder.applicationContext(application).build()))
            .bindNow();
    origin = "http://127.0.0.1:" + gateway.port();
  }

  @AfterEach
  void stop() {
    slowRelease.countDown();
    if (gateway != null) gateway.disposeNow();
    if (loops != null) loops.disposeLater().block();
    if (application != null) application.close();
    if (identity != null) identity.stop(0);
    http.shutdownNow();
  }

  private HttpResponse<String> get(String path, String token, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(origin + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void browserConsentUsesExactIdentityRouteWithoutGatewaySession() throws Exception {
    var response =
        get(
            "/identity/consent?client_id=public-client&scope=profile&state=fixture",
            null,
            "Cookie",
            "IDENTITY_SSO=fixture",
            "X-User-Id",
            "forged-user");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.get("sso_cookie_preserved").asBoolean()).isTrue();
    assertThat(body.get("forged_headers_absent").asBoolean()).isTrue();
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(readers).isEmpty();
    assertThat(get("/identity/not-published", null).statusCode()).isEqualTo(401);
  }

  @Test
  void fixedAnonymousBrandDoesNotRequireBearerOrCreateSession() throws Exception {
    var brand =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/identity/admin-config"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(brand.statusCode()).isEqualTo(200);
    assertThat(readers).isEmpty();
    assertThat(brand.headers().allValues("Set-Cookie")).isEmpty();
  }

  @Test
  void actualRouteChoosesIndependentAudienceIdentityAndKeepsTrustedReactiveContext()
      throws Exception {
    var response =
        get(
            "/api/v1/documents/item?audience=analysis",
            "user-token",
            "X-User-Id",
            "forged",
            "X-Tenant-Id",
            "forged",
            "X-Service-Authorization",
            "Bearer forged",
            "X-Request-Id",
            "gateway-auth-1");
    assertThat(response.statusCode()).isEqualTo(200);
    var data = JSON.readTree(response.body());
    assertThat(data.get("subject").asString()).isEqualTo(USER);
    assertThat(data.get("tenant").asString()).isEqualTo(TENANT);
    for (String flag : List.of("forged_headers_absent", "token_preserved"))
      assertThat(data.get(flag).asBoolean()).as(flag).isTrue();
    assertThat(data.get("request_id").asString()).isEqualTo("gateway-auth-1");
    assertThat(readers).containsExactly("gateway-doc:s-doc");
    assertThat(get("/api/v1/analysis-jobs/job", "user-token").statusCode()).isEqualTo(200);
    assertThat(readers).containsExactly("gateway-doc:s-doc", "gateway-analysis:s-analysis");
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(
            application
                .getBean(com.ken.platform.gateway.security.GatewayRoutes.class)
                .definitions())
        .extracting(route -> route.getUri().toString())
        .containsExactly("lb://document-service", "lb://analysis-service");
  }

  @Test
  void invalidDuplicateCookieAndWrongAudienceNeverBecomeAuthenticatedOrCached() throws Exception {
    assertThat(
            get("/api/v1/documents/item", null, "Cookie", "BFF=forged", "X-User-Id", USER)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            get("/api/v1/documents/item", "user-token", "Authorization", "Bearer invalid")
                .statusCode())
        .isEqualTo(401);
    assertThat(readers).isEmpty();
    assertThat(get("/api/v1/documents/item", "wrong-audience").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/documents/item", "invalid").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/documents/item", "user-token").statusCode()).isEqualTo(200);
    active.set(false);
    assertThat(get("/api/v1/documents/item", "user-token").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/not-registered/item", "user-token").statusCode()).isEqualTo(404);
  }

  @Test
  void dependencyFailureIsStable503AndProtocolBasicIsNotConsumedByBearerChain() throws Exception {
    var failure = get("/api/v1/documents/item", "unavailable");
    assertThat(failure.statusCode()).isEqualTo(503);
    var body = JSON.readTree(failure.body());
    assertThat(body.get("code").asString()).isEqualTo("IDENTITY_UNAVAILABLE");
    assertThat(body.get("request_id").asString())
        .isEqualTo(failure.headers().firstValue("X-Request-Id").orElseThrow());
    assertThat(failure.body()).doesNotContain("s-doc", "gateway-doc", "unavailable");
    int before = readers.size();
    var protocol = get("/oauth2/token", null, "Authorization", "Basic fixture");
    assertThat(protocol.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(protocol.body()).get("protocol_basic_preserved").asBoolean()).isTrue();
    assertThat(readers).hasSize(before);
    assertThat(get("/internal/v1/jobs", "user-token").statusCode()).isEqualTo(403);
  }

  @Test
  void healthAndSimultaneousUsersHaveNoCrossRequestIdentityLeak() throws Exception {
    var first =
        http.sendAsync(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/documents/item"))
                .header("Authorization", "Bearer user-token")
                .header("X-Request-Id", "first-user")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var second =
        http.sendAsync(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/documents/item"))
                .header("Authorization", "Bearer user-second")
                .header("X-Request-Id", "second-user")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(JSON.readTree(first.join().body()).get("subject").asString()).isEqualTo(USER);
    assertThat(JSON.readTree(second.join().body()).get("subject").asString())
        .isEqualTo("33333333-3333-4333-8333-333333333333");
    var health = get("/actuator/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(health.body()).get("subject").isNull()).isTrue();
    assertThat(get("/actuator/env", null).statusCode()).isIn(401, 403);
  }

  @Test
  void dependencyChecksWatchRoutesBeforeVerifyingEveryReader() throws Exception {
    application
        .getBean(com.ken.platform.foundation.discovery.PlatformReadinessCheck.class)
        .verify();
    var runtime = application.getBean(NacosRuntime.class);
    var order = inOrder(runtime);
    order.verify(runtime).watch("analysis-service");
    order.verify(runtime).watch("document-service");
    order.verify(runtime).watch("identity-service");
    assertThat(readers)
        .containsExactlyInAnyOrder("gateway-doc:s-doc", "gateway-analysis:s-analysis");
  }

  @Test
  void pendingIntrospectionCannotBlockHealthOnTheSingleGatewayEventLoop() throws Exception {
    var pending =
        http.sendAsync(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/documents/item"))
                .header("Authorization", "Bearer slow-token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(slowEntered.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      var health =
          http.sendAsync(
                  HttpRequest.newBuilder(URI.create(origin + "/actuator/health")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .get(2, TimeUnit.SECONDS);
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(pending.isDone()).isFalse();
    } finally {
      slowRelease.countDown();
    }
    assertThat(pending.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
  }

  @Test
  void generatedAndSanitizedMetadataIsProjectedToTheActualDownstreamRequest() throws Exception {
    var response =
        get(
            "/api/v1/documents/item",
            "user-token",
            "X-Request-Id",
            "invalid id",
            "traceparent",
            "invalid",
            "X-Request-Deadline",
            "99999999999999999");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.get("metadata_forwarded").asBoolean()).isTrue();
    assertThat(body.get("request_id").asString())
        .isNotEqualTo("invalid id")
        .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
  }

  @Test
  void changedRestartSnapshotStopsReadinessBeforeWatchingOrAuthenticating() throws Exception {
    var runtime = application.getBean(NacosRuntime.class);
    when(runtime.parameters()).thenReturn(RuntimeParameters.defaults());
    assertThatThrownBy(
            () ->
                application
                    .getBean(com.ken.platform.foundation.discovery.PlatformReadinessCheck.class)
                    .verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Gateway 路由依赖或独立内省身份检查失败");
    verify(runtime, never()).watch(anyString());
    assertThat(readers).isEmpty();
  }
}
