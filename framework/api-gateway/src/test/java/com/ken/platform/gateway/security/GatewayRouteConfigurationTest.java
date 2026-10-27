package com.ken.platform.gateway.security;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.GatewayRouteSpec;
import com.ken.platform.foundation.config.RuntimeParameters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.server.PathContainer;

class GatewayRouteConfigurationTest {
  @Test
  void alternateSpringRouteSourceIsRejectedInsteadOfMergingPublicEntrances() {
    var configuration = new GatewaySecurityConfiguration();
    var properties = new GatewayProperties();
    assertThatCode(
            () -> configuration.platformGatewayRoutes(RuntimeParameters.defaults(), properties))
        .doesNotThrowAnyException();
    properties.getRoutes().add(new RouteDefinition());
    assertThatThrownBy(
            () -> configuration.platformGatewayRoutes(RuntimeParameters.defaults(), properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能另设 Spring 路由");
  }

  @Test
  void routeMatchingUsesFixedSegmentsAndOnlyExposesDeclaredProtocolPaths() {
    var parameters =
        new RuntimeParameters(
            "INFO",
            2,
            10,
            1,
            true,
            List.of(
                new GatewayRouteSpec(
                    "documents", "/api/v1/documents/**", "document-service", "documents"),
                new GatewayRouteSpec("token", "/oauth2/token", "identity-service", null)));
    var routes = new GatewayRoutes(parameters);
    assertThat(routes.api(PathContainer.parsePath("/api/v1/documents/item")).audience())
        .isEqualTo("documents");
    assertThat(routes.api(PathContainer.parsePath("/api/v1/documents-other/item"))).isNull();
    assertThat(routes.api(PathContainer.parsePath("/internal/v1/documents"))).isNull();
    assertThat(routes.publicPaths()).containsExactly("/oauth2/token");
  }
}
