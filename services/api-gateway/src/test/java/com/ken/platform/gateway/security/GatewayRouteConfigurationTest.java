package com.ken.platform.gateway.security;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.gateway.RouteFixtures;
import com.ken.platform.gateway.bff.BffNamespace;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouteConfigurationTest {
  @Test
  void nativeRoutesAreTheSoleSourceAndBffCopiesTheirAudienceAndLogicalId() {
    var params =
        RouteFixtures.parameters(
            RouteFixtures.route(
                "documents", "/api/v1/documents/**", "document-service", "documents"));
    var routes = new GatewayRoutes(RouteFixtures.properties(params));
    assertThat(routes.definitions()).hasSize(1);
    var derived = routes.bffRoutes(List.of(BffNamespace.WEB)).getFirst();
    assertThat(derived.getMetadata())
        .containsEntry("platform.logical-route", "documents")
        .containsEntry("bff.audience", "documents");
    assertThat(derived.getUri()).isEqualTo(routes.definitions().getFirst().getUri());
    assertThat(routes.publicPaths()).contains("/oauth2/token").doesNotContain("/internal/**");
  }

  @Test
  void internalPathsExternalTargetsAndMissingAudienceAreRejected() {
    for (String value :
        List.of(
            RouteFixtures.route("all", "/**", "document-service", "documents"),
            RouteFixtures.route("internal", "/internal/**", "document-service", "documents"),
            RouteFixtures.route("loop", "/api/v1/documents/**", "api-gateway", "documents"),
            RouteFixtures.route("wrong", "/api/v1/identity/**", "document-service", "documents"),
            "{id: missing, uri: 'lb://document-service', predicates: ['Path=/api/v1/documents/**']}")) {
      assertThatThrownBy(
              () -> new GatewayRoutes(RouteFixtures.properties(RouteFixtures.parameters(value))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void nativeLimiterBindingPreservesDottedKeysAndRejectsArbitraryExpression() {
    String rule =
        "{id: documents, uri: 'lb://document-service', predicates: ['Path=/api/v1/documents/**'], metadata: {platform.audience: documents}, filters: [{name: RequestRateLimiter, args: {key-resolver: '#{@tenantOrClientKeyResolver}', rate-limiter: '#{@platformRedisRateLimiter}', redis-rate-limiter.replenishRate: 20, redis-rate-limiter.burstCapacity: 40, redis-rate-limiter.requestedTokens: 1}}]}";
    var routes = new GatewayRoutes(RouteFixtures.properties(RouteFixtures.parameters(rule)));
    assertThat(routes.definitions().getFirst().getFilters().getFirst().getArgs())
        .containsEntry("redis-rate-limiter.replenishRate", "20");
    assertThat(
            routes
                .bffRoutes(List.of(BffNamespace.WEB))
                .getFirst()
                .getFilters()
                .getFirst()
                .getArgs())
        .isEqualTo(routes.definitions().getFirst().getFilters().getFirst().getArgs());
    assertThatThrownBy(
            () ->
                new GatewayRoutes(
                    RouteFixtures.properties(
                        RouteFixtures.parameters(
                            rule.replace("@tenantOrClientKeyResolver", "T(java.lang.Runtime)")))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
