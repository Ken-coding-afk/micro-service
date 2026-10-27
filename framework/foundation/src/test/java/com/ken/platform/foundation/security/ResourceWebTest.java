package com.ken.platform.foundation.security;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.NacosSettings;
import com.ken.platform.foundation.config.PlatformAutoConfiguration;
import com.ken.platform.foundation.config.PlatformResourceSecurityAutoConfiguration;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.web.ServletRequestContextFilter;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** 真实 Servlet/Security 链；此文件的内省 HTTP stub 不替代真实身份服务联调。 */
class ResourceWebTest {
  @TempDir Path directory;
  private HttpServer identity;
  private AnnotationConfigServletWebServerApplicationContext application;
  private final AtomicInteger calls = new AtomicInteger();
  private String origin;
  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @Import({PlatformAutoConfiguration.class, PlatformResourceSecurityAutoConfiguration.class})
  static class Configuration {
    @Bean
    TomcatServletWebServerFactory webServerFactory() {
      return new TomcatServletWebServerFactory(0);
    }

    @Bean
    DispatcherServlet dispatcherServlet() {
      return new DispatcherServlet();
    }

    @Bean
    ServletRegistrationBean<DispatcherServlet> dispatcherRegistration(DispatcherServlet servlet) {
      return new ServletRegistrationBean<>(servlet, "/");
    }

    @Bean
    FilterRegistrationBean<Filter> securityRegistration(
        @Qualifier("springSecurityFilterChain") Filter filter) {
      var bean = new FilterRegistrationBean<>(filter);
      bean.setOrder(-100);
      bean.setDispatcherTypes(
          DispatcherType.REQUEST,
          DispatcherType.ASYNC,
          DispatcherType.ERROR,
          DispatcherType.FORWARD);
      return bean;
    }

    @Bean
    FilterRegistrationBean<ServletRequestContextFilter> requestContext(
        Clock clock, RuntimeParameters parameters) {
      var filter = new ServletRequestContextFilter(clock, parameters);
      var bean = new FilterRegistrationBean<>(filter);
      bean.setOrder(filter.getOrder());
      return bean;
    }

    @Bean
    Routes routes() {
      return new Routes();
    }
  }

  @RestController
  static class Routes {
    @GetMapping("/actuator/health")
    Map<String, Object> health() {
      return Map.of("status", "UP", "anonymous", CallContexts.current().principalId() == null);
    }

    @GetMapping("/api/v1/probe")
    @RequirePermission(value = "document:read", scope = "documents.read")
    Map<String, Object> read() {
      return context();
    }

    @PostMapping("/api/v1/probe")
    @RequirePermission(value = "document:read", scope = "documents.read")
    Map<String, Object> post() {
      return context();
    }

    @GetMapping("/api/v1/undeclared")
    Map<String, Object> undeclared() {
      return Map.of("unsafe", true);
    }

    @GetMapping("/internal/v1/forward")
    @RequirePermission(value = "document:read", scope = "documents.read")
    @RequireServicePermission(value = "internal:forward", scope = "internal.forward")
    Map<String, Object> forward() {
      return context();
    }

    @GetMapping("/internal/v1/job")
    @RequirePermission(
        value = "internal:forward",
        scope = "internal.forward",
        subject = RequirePermission.Subject.SERVICE)
    Map<String, Object> job() {
      return context();
    }

    private Map<String, Object> context() {
      var value = CallContexts.current();
      return Map.of(
          "subject",
          value.principalId(),
          "tenant",
          value.tenantId().toString(),
          "request_id",
          value.requestId(),
          "primary_token_preserved",
          value.accessToken().startsWith("user"));
    }
  }

  @BeforeEach
  void start() throws Exception {
    identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    identity.createContext(
        "/oauth2/introspect",
        exchange -> {
          calls.incrementAndGet();
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String token =
              URLDecoder.decode(form.substring("token=".length()), StandardCharsets.UTF_8);
          int status = token.equals("unavailable") ? 503 : 200;
          byte[] body = JSON.writeValueAsBytes(claims(token));
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    identity.start();
    Path secret = directory.resolve("resource.password");
    Files.writeString(secret, "resource-fixture");
    application = new AnnotationConfigServletWebServerApplicationContext();
    application
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "resource-bindings",
                Map.of(
                    "SERVICE_NAME",
                    "document-service",
                    "ROLE",
                    "api",
                    "APP_ENV",
                    "local",
                    "PUBLIC_ORIGIN",
                    "http://localhost:18080",
                    "RESOURCE_AUDIENCE",
                    "document-service",
                    "INTROSPECTION_CLIENT_ID",
                    "reader",
                    "INTROSPECTION_CLIENT_SECRET_FILE",
                    secret.toString())));
    application.registerBean(
        NacosSettings.class,
        () ->
            NacosSettings.from(
                Map.ofEntries(
                    Map.entry("PROJECT_CODE", "sample"),
                    Map.entry("APP_ENV", "local"),
                    Map.entry("SERVICE_NAME", "document-service"),
                    Map.entry("NACOS_NAMESPACE", "sample-local"),
                    Map.entry("NACOS_SERVER_ADDR", "127.0.0.1:8848"),
                    Map.entry("NACOS_RUNTIME_USER", "runtime"),
                    Map.entry("NACOS_RUNTIME_PASSWORD_SECRET_FILE", secret.toString()),
                    Map.entry("SERVICE_ADVERTISE_HOST", "192.0.2.17"),
                    Map.entry("SERVICE_ADVERTISE_PORT", "8082"))));
    application.register(Configuration.class);
    application.refresh();
    application
        .getBean(ServiceDiscovery.class)
        .confirm(
            "identity-service",
            List.of(new ServiceInstance("127.0.0.1", identity.getAddress().getPort(), "http")));
    origin = "http://127.0.0.1:" + application.getWebServer().getPort();
  }

  @AfterEach
  void stop() {
    if (application != null) application.close();
    if (identity != null) identity.stop(0);
    http.shutdownNow();
  }

  private Map<String, Object> claims(String token) {
    if (token.equals("invalid")) return Map.of("active", false);
    var raw = CurrentPrincipalTest.valid();
    raw.put("exp", java.time.Instant.now().getEpochSecond() + 600);
    raw.put("iat", java.time.Instant.now().getEpochSecond());
    if (token.equals("no-permission")) {
      raw.put("permissions", List.of());
      raw.put("data_scopes", Map.of());
    }
    if (token.equals("wrong-scope")) raw.put("scope", "documents.write");
    if (token.equals("user-second")) {
      raw.put("sub", "33333333-3333-4333-8333-333333333333");
      raw.put("tenant_id", "44444444-4444-4444-8444-444444444444");
    }
    if (token.startsWith("machine")) {
      String tenant =
          token.equals("machine-foreign")
              ? "44444444-4444-4444-8444-444444444444"
              : "22222222-2222-4222-8222-222222222222";
      raw.put("sub", "fixture-machine");
      raw.put("client_id", "fixture-machine");
      raw.put("principal_type", "service");
      raw.put("tenant_id", tenant);
      raw.put("allowed_tenant_ids", List.of(tenant));
      raw.put("scope", "documents.read internal.forward");
      raw.put("permissions", List.of("document:read", "internal:forward"));
      raw.put("data_scopes", Map.of("document:read", "TENANT"));
    }
    return raw;
  }

  private HttpResponse<String> request(String path, String token, String... headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).GET();
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void bearerAndPermissionAreRequiredAndFakeHeadersOrCookieCannotAuthenticate() throws Exception {
    assertThat(
            request(
                    "/api/v1/probe",
                    null,
                    "X-User-Id",
                    "forged",
                    "X-Tenant-Id",
                    "22222222-2222-4222-8222-222222222222",
                    "Cookie",
                    "IDENTITY_SSO=forged")
                .statusCode())
        .isEqualTo(401);
    assertThat(request("/api/v1/probe", "invalid").statusCode()).isEqualTo(401);
    assertThat(request("/api/v1/probe", "no-permission").statusCode()).isEqualTo(403);
    assertThat(request("/api/v1/probe", "wrong-scope").statusCode()).isEqualTo(403);
    assertThat(request("/api/v1/probe", "machine-token").statusCode()).isEqualTo(403);
    var success =
        request(
            "/api/v1/probe?tenant_id=forged",
            "user-token",
            "X-Tenant-Id",
            "forged",
            "X-Request-Id",
            "request-web-1");
    assertThat(success.statusCode()).isEqualTo(200);
    var data = JSON.readTree(success.body());
    assertThat(data.get("tenant").asString()).isEqualTo("22222222-2222-4222-8222-222222222222");
    assertThat(data.get("request_id").asString())
        .isEqualTo(success.headers().firstValue("X-Request-Id").orElseThrow());
    assertThat(success.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(request("/api/v1/undeclared", "user-token").statusCode()).isEqualTo(403);
  }

  @Test
  void healthIsAnonymousButProtectedDiagnosticsAreDeniedAndBearerPostNeedsNoCookieCsrf()
      throws Exception {
    var health = request("/actuator/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(health.body()).get("anonymous").asBoolean()).isTrue();
    assertThat(request("/actuator/env", "user-token").statusCode()).isEqualTo(403);
    var post =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/probe"))
                .header("Authorization", "Bearer user-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(200);
    assertThat(request("/actuator/health", null).statusCode()).isEqualTo(200);
  }

  @Test
  void internalUserRouteRequiresIndependentMachineAndExactTenantWithoutReplacingUser()
      throws Exception {
    assertThat(request("/internal/v1/forward", "user-token").statusCode()).isEqualTo(401);
    assertThat(
            request(
                    "/internal/v1/forward",
                    "user-token",
                    "X-Service-Authorization",
                    "Bearer user-token")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "/internal/v1/forward",
                    "user-token",
                    "X-Service-Authorization",
                    "Bearer machine-foreign")
                .statusCode())
        .isEqualTo(403);
    var response =
        request(
            "/internal/v1/forward",
            "user-token",
            "X-Service-Authorization",
            "Bearer machine-token");
    assertThat(response.statusCode()).isEqualTo(200);
    var value = JSON.readTree(response.body());
    assertThat(value.get("subject").asString()).isEqualTo("11111111-1111-4111-8111-111111111111");
    assertThat(value.get("primary_token_preserved").asBoolean()).isTrue();
    assertThat(request("/internal/v1/job", "machine-token").statusCode()).isEqualTo(200);
  }

  @Test
  void invalidDuplicateBearerAndDependencyFailureHaveStableErrors() throws Exception {
    int before = calls.get();
    var duplicate = request("/api/v1/probe", "user-token", "Authorization", "Bearer invalid");
    assertThat(duplicate.statusCode()).isEqualTo(401);
    assertThat(calls.get()).isEqualTo(before);
    var unavailable = request("/api/v1/probe", "unavailable");
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(unavailable.body()).get("code").asString())
        .isEqualTo("IDENTITY_UNAVAILABLE");
    assertThat(unavailable.body()).doesNotContain("resource-fixture", "Authorization");
    assertThat(
            request("/api/v1/probe", null).headers().firstValue("WWW-Authenticate").orElseThrow())
        .contains("Bearer");
  }
}
