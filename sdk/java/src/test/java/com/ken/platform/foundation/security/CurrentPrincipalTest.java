package com.ken.platform.foundation.security;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import tools.jackson.databind.json.JsonMapper;

class CurrentPrincipalTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final long NOW = 1793000010L;
  static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

  @SuppressWarnings("unchecked")
  static Stream<Arguments> cases() throws Exception {
    var fixture =
        JSON.readTree(
            Files.readString(Path.of("..", "tests", "fixtures", "introspection-v1.json")));
    assertThat(fixture.get("test_only").asBoolean()).isTrue();
    assertThat(fixture.get("now").asLong()).isEqualTo(NOW);
    return java.util.stream.StreamSupport.stream(fixture.get("cases").spliterator(), false)
        .map(
            row ->
                Arguments.of(
                    row.get("name").asString(),
                    JSON.convertValue(row.get("claims"), Map.class),
                    row.get("status").asInt()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void sharedCrossLanguageContract(String name, Map<String, Object> claims, int expected) {
    if (expected == 200) {
      var principal =
          CurrentPrincipal.parse(claims, "http://localhost:18080", "document-service", CLOCK);
      assertThat(principal.subject()).isEqualTo(claims.get("sub"));
      Object expiration = principal.getAttribute("exp");
      assertThat(expiration).isInstanceOf(Instant.class);
      var provider = new OpaqueTokenAuthenticationProvider(token -> principal);
      assertThat(
              provider
                  .authenticate(new BearerTokenAuthenticationToken("fixture-token"))
                  .isAuthenticated())
          .isTrue();
    } else {
      var failure =
          catchThrowable(
              () ->
                  CurrentPrincipal.parse(
                      claims, "http://localhost:18080", "document-service", CLOCK));
      if (expected == 401) assertThat(failure).isInstanceOf(BadOpaqueTokenException.class);
      else assertThat(failure).isExactlyInstanceOf(OAuth2IntrospectionException.class);
      assertThat(failure).hasNoCause();
    }
  }

  @Test
  void scopesPermissionTypeAndDataRangeAreSeparateAndClaimsAreImmutable() {
    var principal =
        CurrentPrincipal.parse(valid(), "http://localhost:18080", "document-service", CLOCK);
    principal.require("documents.read", "document:read", "user");
    assertThat(principal.requireTenant())
        .isEqualTo(java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"));
    assertThat(principal.dataScope("document:read")).isEqualTo(DataScope.SELF);
    for (Runnable action :
        java.util.List.<Runnable>of(
            () -> principal.require("documents.write", "document:read", "user"),
            () -> principal.require("documents.read", "document:write", "user"),
            () -> principal.require("documents.read", "document:read", "service"),
            () -> principal.dataScope("document:write")))
      assertThatThrownBy(action::run)
          .isInstanceOfSatisfying(
              com.ken.platform.foundation.http.PlatformException.class,
              error -> assertThat(error.status()).isEqualTo(403));
    assertThatThrownBy(() -> principal.permissions().add("document:write"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> principal.getAttributes().put("tenant_id", "another"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  static Map<String, Object> valid() {
    return new java.util.HashMap<>(
        Map.ofEntries(
            Map.entry("active", true),
            Map.entry("iss", "http://localhost:18080"),
            Map.entry("sub", "11111111-1111-4111-8111-111111111111"),
            Map.entry("client_id", "fixture-web"),
            Map.entry("aud", java.util.List.of("document-service")),
            Map.entry("exp", NOW + 600),
            Map.entry("iat", NOW),
            Map.entry("scope", "documents.read profile"),
            Map.entry("principal_type", "user"),
            Map.entry("tenant_id", "22222222-2222-4222-8222-222222222222"),
            Map.entry("permissions", java.util.List.of("document:read")),
            Map.entry("data_scopes", Map.of("document:read", "SELF"))));
  }
}
