package com.ken.platform.foundation.security;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.CallContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;

class ResourceIntrospectorTest {
  @TempDir Path directory;

  private ResourceIdentitySettings settings() throws Exception {
    Path secret = directory.resolve("introspection.password");
    Files.writeString(secret, "private-fixture:+ /\n");
    return new ResourceIdentitySettings(
        "local", "http://localhost:18080", "document-service", "reader:document", secret);
  }

  private ServiceDiscovery discovery(HttpServer server) {
    var discovery = new ServiceDiscovery(System::nanoTime);
    discovery.confirm(
        "identity-service",
        List.of(new ServiceInstance("127.0.0.1", server.getAddress().getPort(), "http")));
    return discovery;
  }

  @Test
  void startupRequiresAuthenticatedInactiveResponseAndRejectsActiveOrUnavailable()
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var body = new AtomicReference<>("{\"active\":false}".getBytes(StandardCharsets.UTF_8));
    var status = new AtomicInteger(200);
    server.createContext(
        "/oauth2/introspect",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
          byte[] response = body.get();
          exchange.sendResponseHeaders(status.get(), response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try (var client =
        new ResourceIntrospector(
            discovery(server),
            settings(),
            RuntimeParameters.defaults(),
            CurrentPrincipalTest.CLOCK)) {
      client.verifyIdentity();
      body.set(CurrentPrincipalTest.JSON.writeValueAsBytes(CurrentPrincipalTest.valid()));
      assertThatThrownBy(client::verifyIdentity).isInstanceOf(OAuth2IntrospectionException.class);
      status.set(503);
      assertThatThrownBy(client::verifyIdentity).isInstanceOf(OAuth2IntrospectionException.class);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void eachCallUsesOwnEncodedBasicFormAndNoCacheOrUntrustedIdentityHeaders() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    var body =
        new AtomicReference<>(
            CurrentPrincipalTest.JSON.writeValueAsBytes(CurrentPrincipalTest.valid()));
    var authorization = new AtomicReference<String>();
    var form = new AtomicReference<String>();
    var requestId = new AtomicReference<String>();
    var identityHeader = new AtomicReference<String>();
    server.createContext(
        "/oauth2/introspect",
        exchange -> {
          calls.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
          identityHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
          form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = body.get();
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try (var client =
        new ResourceIntrospector(
            discovery(server),
            settings(),
            RuntimeParameters.defaults(),
            CurrentPrincipalTest.CLOCK)) {
      var context =
          new CallContext(
              "introspect-read-1",
              null,
              null,
              "DO_NOT_FORWARD_AS_AUTH",
              null,
              Instant.ofEpochSecond(CurrentPrincipalTest.NOW + 5));
      var principal = client.introspectAsync("fixture-token", context).join();
      assertThat(principal.permissions()).containsExactly("document:read");
      assertThat(authorization.get())
          .isEqualTo(
              "Basic "
                  + java.util.Base64.getEncoder()
                      .encodeToString(
                          "reader%3Adocument:private-fixture%3A%2B+%2F"
                              .getBytes(StandardCharsets.UTF_8)));
      assertThat(form.get()).isEqualTo("token=fixture-token");
      assertThat(requestId.get()).isEqualTo("introspect-read-1");
      assertThat(identityHeader.get()).isNull();
      body.set("{\"active\":false}".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> client.introspect("fixture-token"))
          .isInstanceOf(BadOpaqueTokenException.class);
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 500, 503, 200})
  void upstreamErrorsMalformedBodyAndRedirectNeverBecomeAnAnonymousSuccess(int status)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.getResponseHeaders().set("Location", "/should-not-follow");
          byte[] response = "DO_NOT_ECHO_UPSTREAM_OR_SECRET".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try (var client =
        new ResourceIntrospector(
            discovery(server),
            settings(),
            RuntimeParameters.defaults(),
            CurrentPrincipalTest.CLOCK)) {
      assertThatThrownBy(() -> client.introspect("fixture-token"))
          .isExactlyInstanceOf(OAuth2IntrospectionException.class)
          .hasNoCause()
          .hasMessageNotContaining("DO_NOT_ECHO")
          .hasMessageNotContaining("private-fixture");
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void expiredCallerBudgetAndInvalidBearerDoNotMakeARequest() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.close();
        });
    server.start();
    try (var client =
        new ResourceIntrospector(
            discovery(server),
            settings(),
            RuntimeParameters.defaults(),
            CurrentPrincipalTest.CLOCK)) {
      var context =
          new CallContext(
              "expired-budget",
              null,
              null,
              null,
              null,
              Instant.ofEpochSecond(CurrentPrincipalTest.NOW - 1));
      assertThatThrownBy(() -> client.introspectAsync("fixture-token", context).join())
          .hasCauseInstanceOf(OAuth2IntrospectionException.class);
      assertThatThrownBy(() -> client.introspect("not a valid\nBearer"))
          .isInstanceOf(BadOpaqueTokenException.class);
      assertThat(calls.get()).isZero();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void responseBudgetIncludesTheWholeResponseAndPostIsNotRetried() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try {
            Thread.sleep(500);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();
    try (var client =
        new ResourceIntrospector(
            discovery(server),
            settings(),
            new RuntimeParameters("INFO", 0.1, 0.1, 1, true),
            CurrentPrincipalTest.CLOCK)) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> client.introspect("fixture-token"))
          .isExactlyInstanceOf(OAuth2IntrospectionException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
      assertThat(calls.get()).isLessThanOrEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "issuer",
        "path",
        "port",
        "environment",
        "audience",
        "client",
        "relative",
        "missing",
        "empty",
        "literal"
      })
  void malformedOrSecretSecondSourceDoesNotFallback(String scenario) throws Exception {
    var settings = settings();
    var env =
        new MockEnvironment()
            .withProperty("APP_ENV", "local")
            .withProperty("PUBLIC_ORIGIN", settings.issuer())
            .withProperty("RESOURCE_AUDIENCE", settings.audience())
            .withProperty("INTROSPECTION_CLIENT_ID", settings.clientId())
            .withProperty("INTROSPECTION_CLIENT_SECRET_FILE", settings.secretFile().toString());
    switch (scenario) {
      case "issuer" -> env.setProperty("PUBLIC_ORIGIN", "http://untrusted.example");
      case "path" -> env.setProperty("PUBLIC_ORIGIN", "https://valid.example/private");
      case "port" -> env.setProperty("PUBLIC_ORIGIN", "https://valid.example:65536");
      case "environment" -> env.setProperty("APP_ENV", "production");
      case "audience" -> env.setProperty("RESOURCE_AUDIENCE", "*");
      case "client" -> env.setProperty("INTROSPECTION_CLIENT_ID", "reader\r\nother");
      case "relative" -> env.setProperty("INTROSPECTION_CLIENT_SECRET_FILE", "password");
      case "missing" ->
          env.setProperty(
              "INTROSPECTION_CLIENT_SECRET_FILE", directory.resolve("missing").toString());
      case "empty" -> Files.writeString(settings.secretFile(), " ");
      default -> env.setProperty("INTROSPECTION_CLIENT_SECRET", "DO_NOT_ECHO");
    }
    assertThatThrownBy(() -> ResourceIdentitySettings.from(env))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause()
        .hasMessageNotContaining("private-fixture")
        .hasMessageNotContaining("DO_NOT_ECHO");
    assertThat(settings.toString()).isEqualTo("ResourceIdentitySettings[protected]");
  }
}
