package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.json.JsonMapper;

class BffMonitoringTest {
  @Test
  void proxyStripsBrowserCredentialsAndRechecksRevocation() {
    var received =
        new java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.http.HttpHeaders>();
    var server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) -> {
                  received.set(request.requestHeaders().copy());
                  return response
                      .header("Content-Type", "application/json")
                      .header(
                          "Content-Security-Policy",
                          "script-src 'nonce-fixture'; frame-ancestors 'self'")
                      .sendString(reactor.core.publisher.Mono.just("{\"database\":\"ok\"}"));
                })
            .bindNow();
    try {
      String subject = java.util.UUID.randomUUID().toString();
      var authorities =
          java.util.List.of(
              new org.springframework.security.core.authority.SimpleGrantedAuthority(
                  "ROLE_BFF_USER"));
      var user =
          new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
              authorities, java.util.Map.of("sub", subject, "principal_type", "platform"), "sub");
      var auth =
          new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
              user, authorities, "platform");
      var registration =
          org.springframework.security.oauth2.client.registration.ClientRegistration
              .withRegistrationId("platform")
              .clientId("identity-admin-platform")
              .clientSecret("secret")
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType
                      .AUTHORIZATION_CODE)
              .redirectUri("https://app.example/callback")
              .authorizationUri("https://app.example/authorize")
              .tokenUri("https://app.example/token")
              .build();
      var access =
          new org.springframework.security.oauth2.core.OAuth2AccessToken(
              org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
              "never-forward-this-token",
              java.time.Instant.now(),
              java.time.Instant.now().plusSeconds(60));
      var client =
          new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(
              registration, subject, access);
      var tokens = mock(BffTokens.class);
      var identity = mock(BffIdentityClient.class);
      when(tokens.current(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
          .thenReturn(reactor.core.publisher.Mono.just(client));
      when(identity.application(
              org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn(
              reactor.core.publisher.Mono.just(
                  new BffIdentityClient.Identity(
                      subject,
                      "platform",
                      null,
                      "identity-admin-platform",
                      java.util.Set.of("identity-service"),
                      true)));
      var env =
          new MockEnvironment()
              .withProperty("COMPOSE_PROFILES", "monitoring")
              .withProperty("PUBLIC_ORIGIN", "https://app.example")
              .withProperty("GRAFANA_INTERNAL_ORIGIN", "http://127.0.0.1:" + server.port());
      var endpoint = new BffMonitoring(tokens, identity, env);
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get(BffMonitoring.PREFIX + "api/health")
                  .header("Authorization", "Bearer forged")
                  .header("Cookie", "forged=cookie")
                  .header("X-WEBAUTH-USER", "forged-user")
                  .header("X-WEBAUTH-ROLE", "Admin"));
      endpoint
          .relay(exchange.mutate().principal(reactor.core.publisher.Mono.just(auth)).build())
          .block();
      assertThat(received.get().get("Authorization")).isNull();
      assertThat(received.get().get("Cookie")).isNull();
      assertThat(received.get().get("X-WEBAUTH-USER")).isEqualTo(subject);
      assertThat(received.get().get("X-WEBAUTH-ROLE")).isEqualTo("Viewer");
      assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
          .contains("nonce-fixture");
      received.set(null);
      when(identity.application(
              org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn(
              reactor.core.publisher.Mono.just(
                  new BffIdentityClient.Identity(
                      subject,
                      "platform",
                      null,
                      "identity-admin-platform",
                      java.util.Set.of("identity-service"),
                      false)));
      var revoked =
          MockServerWebExchange.from(
              MockServerHttpRequest.get(BffMonitoring.PREFIX + "api/health"));
      assertThatThrownBy(
              () ->
                  endpoint
                      .relay(
                          revoked
                              .mutate()
                              .principal(reactor.core.publisher.Mono.just(auth))
                              .build())
                      .block())
          .isInstanceOf(PlatformException.class);
      assertThat(received.get()).isNull();
    } finally {
      server.disposeNow();
    }
  }

  @Test
  void optionalProfileDoesNotExpandExistingPlatformLoginScopes() {
    assertThat(BffClientSettings.platformScopes(new MockEnvironment()))
        .doesNotContain("platform.monitoring");
    assertThat(
            BffClientSettings.platformScopes(
                new MockEnvironment().withProperty("COMPOSE_PROFILES", "monitoring")))
        .contains("platform.monitoring");
  }

  @Test
  void postOnlyAllowsSameOriginDatasourceQuery() {
    var valid =
        MockServerWebExchange.from(
            MockServerHttpRequest.post(BffMonitoring.PREFIX + "api/ds/query")
                .header("Origin", "https://app.example")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{}"));
    assertThatCode(() -> BffMonitoring.checkRequest(valid, "https://app.example"))
        .doesNotThrowAnyException();
    for (String path :
        new String[] {"api/admin/users", "api/ds/query", "../admin", "%2e%2e/admin"}) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post(BffMonitoring.PREFIX + path)
                  .header("Origin", "https://evil.example")
                  .body("{}"));
      assertThatThrownBy(() -> BffMonitoring.checkRequest(exchange, "https://app.example"))
          .isInstanceOf(PlatformException.class);
    }
    var noOrigin =
        MockServerWebExchange.from(
            MockServerHttpRequest.post(BffMonitoring.PREFIX + "api/ds/query").body("{}"));
    assertThatThrownBy(() -> BffMonitoring.checkRequest(noOrigin, "https://app.example"))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void requiresPlatformPermissionAndScopeTogether() {
    var mapper = JsonMapper.builder().build();
    String valid =
        "{\"principal_type\":\"platform\",\"tenant_id\":null,\"audiences\":{\"identity-service\":{\"permissions\":[\"platform:monitoring:read\"],\"scopes\":[\"platform.monitoring\"]}}}";
    assertThat(BffIdentityClient.monitoringAllowed(mapper.readTree(valid))).isTrue();
    for (String invalid :
        new String[] {
          valid.replace("platform:monitoring:read", "platform:audit:read"),
          valid.replace("platform.monitoring", "profile"),
          valid.replace("\"platform\"", "\"user\""),
          valid.replace("null", "\"tenant\"")
        }) {
      assertThat(BffIdentityClient.monitoringAllowed(mapper.readTree(invalid))).isFalse();
    }
  }

  @Test
  void disabledMonitoringDoesNotContactIdentityOrGrafana() {
    var tokens = mock(BffTokens.class);
    var identity = mock(BffIdentityClient.class);
    var endpoint = new BffMonitoring(tokens, identity, new MockEnvironment());
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(BffMonitoring.PREFIX + "api/health"));
    assertThatThrownBy(() -> endpoint.relay(exchange).block())
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error ->
                assertThat(((PlatformException) error).code()).isEqualTo("MONITORING_DISABLED"));
    verifyNoInteractions(tokens, identity);
  }
}
