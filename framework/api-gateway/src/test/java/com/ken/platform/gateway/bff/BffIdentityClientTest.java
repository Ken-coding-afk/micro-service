package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tools.jackson.databind.json.JsonMapper;

/** 真实 HTTP 控制端夹具，仅验证 Gateway 消费合同/错误/预算；不替代真实身份协议。 */
class BffIdentityClientTest {
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private ServiceDiscovery discovery;
  private ClientRegistration client;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile String mode = "normal";
  private final String subject = UUID.randomUUID().toString(),
      tenant = UUID.randomUUID().toString();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/api/v1/identity/me",
        exchange -> {
          calls.incrementAndGet();
          if (mode.equals("inactive")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          if (mode.equals("redirect")) {
            exchange.getResponseHeaders().set("Location", "http://unregistered.invalid/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
          }
          var fields = new java.util.HashMap<String, Object>();
          fields.put("view", "APPLICATION_EFFECTIVE");
          fields.put("sub", subject);
          fields.put("principal_type", mode.equals("wrong-type") ? "platform" : "user");
          fields.put("tenant_id", mode.equals("wrong-type") ? null : tenant);
          fields.put(
              "client_id",
              mode.equals("wrong-client") ? "another-client" : "identity-admin-tenant");
          fields.put(
              "audiences",
              Map.of(mode.equals("changed") ? "document-service" : "identity-service", Map.of()));
          String response =
              mode.equals("malformed")
                  ? "not-json"
                  : mode.equals("oversize")
                      ? "x".repeat(2 * 1024 * 1024 + 1)
                      : JsonMapper.builder()
                          .build()
                          .writeValueAsString(Map.of("code", "OK", "data", fields));
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try {
            if (mode.equals("slow")) {
              for (byte value : body) {
                exchange.getResponseBody().write(value);
                exchange.getResponseBody().flush();
                try {
                  Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            } else exchange.getResponseBody().write(body);
          } catch (java.io.IOException cancelled) {
            /* 消费方预算或大小限制关闭响应。 */
          } finally {
            exchange.close();
          }
        });
    server.start();
    discovery = new ServiceDiscovery(System::nanoTime);
    discovery.confirm(
        "identity-service",
        List.of(new ServiceInstance("127.0.0.1", server.getAddress().getPort(), "http")));
    client =
        ClientRegistration.withRegistrationId("tenant")
            .clientId("identity-admin-tenant")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .clientSecret("consumer-test-only-secret-32-characters")
            .clientAuthenticationMethod(
                org.springframework.security.oauth2.core.ClientAuthenticationMethod
                    .CLIENT_SECRET_BASIC)
            .redirectUri("http://localhost/bff/identity/tenant/callback")
            .authorizationUri("http://localhost/oauth2/authorize")
            .tokenUri("http://identity-service/oauth2/token")
            .scope("profile")
            .build();
  }

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
    if (executor != null) executor.close();
  }

  private BffIdentityClient identity(double budget) {
    return new BffIdentityClient(
        discovery, new RuntimeParameters("INFO", 1, budget, 1, true), Clock.systemUTC());
  }

  @Test
  void applicationIdentityIsFetchedAgainAndIncludesOnlyVerifiedCurrentClientAndNamespace() {
    var service = identity(2);
    var first = service.application("technical-opaque-token", client).block();
    assertThat(first.subject()).isEqualTo(subject);
    assertThat(first.tenant().toString()).isEqualTo(tenant);
    assertThat(first.audiences()).containsExactly("identity-service");
    mode = "changed";
    assertThat(service.application("technical-opaque-token", client).block().audiences())
        .containsExactly("document-service");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void inactiveWrongClientAndWrongPrincipalKindCannotBecomeAuthenticatedUser() {
    for (String value : List.of("inactive", "wrong-client", "wrong-type")) {
      mode = value;
      assertThatThrownBy(() -> identity(2).application("technical-opaque-token", client).block())
          .isInstanceOfSatisfying(
              PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));
    }
  }

  @Test
  void malformedOversizedAndRedirectResponsesAre503WithoutFollowingUnregisteredTarget() {
    for (String value : List.of("malformed", "oversize", "redirect")) {
      mode = value;
      assertThatThrownBy(() -> identity(2).application("technical-opaque-token", client).block())
          .isInstanceOfSatisfying(
              PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    }
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void totalBodyBudgetStopsSlowTrickleInsteadOfRenewingDeadlineOnEveryChunk() {
    mode = "slow";
    long started = System.nanoTime();
    assertThatThrownBy(
            () ->
                identity(0.08)
                    .application("technical-opaque-token", client)
                    .block(Duration.ofSeconds(2)))
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1000);
  }

  @Test
  void expiredDeadlineAndArbitraryControlUrlFailBeforeOutboundRequest() {
    var service = identity(2);
    var call =
        new CallContext("expired-bff", null, null, null, null, Instant.now().minusSeconds(1));
    assertThatThrownBy(
            () ->
                service
                    .application("technical-opaque-token", client)
                    .contextWrite(context -> context.put(CallContext.class, call))
                    .block())
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    assertThatThrownBy(
            () ->
                service
                    .web()
                    .get()
                    .uri("http://unregistered.invalid/other")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block())
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    assertThat(calls.get()).isZero();
  }
}
