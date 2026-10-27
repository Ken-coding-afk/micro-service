package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GatewayRouteSpecTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static Stream<JsonNode> cases() throws Exception {
    var document =
        JSON.readTree(Files.readString(Path.of("../test-fixtures/gateway-routes-v1.json")));
    assertThat(document.get("test_only").asBoolean()).isTrue();
    return document.get("cases").valueStream();
  }

  @ParameterizedTest
  @MethodSource("cases")
  void sharedRouteContract(JsonNode fixture) {
    String service = JSON.writeValueAsString(Map.of("gateway", fixture.get("block")));
    if (fixture.get("valid").asBoolean())
      assertThatCode(
              () ->
                  RuntimeParameters.load(
                      RuntimeParametersTest.SHARED, service, fixture.get("service").asString()))
          .as(fixture.get("name").asString())
          .doesNotThrowAnyException();
    else
      assertThatThrownBy(
              () ->
                  RuntimeParameters.load(
                      RuntimeParametersTest.SHARED, service, fixture.get("service").asString()))
          .as(fixture.get("name").asString())
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void routingIsImmutableRestartOnlyAndCannotBePlacedInSharedConfiguration() {
    String definition =
        "gateway: {routes: [{id: documents, path: '/api/v1/documents/**', service: document-service, audience: documents}]}";
    var routes = RuntimeParameters.load(RuntimeParametersTest.SHARED, definition, "api-gateway");
    assertThat(routes.gatewayRoutes()).hasSize(1);
    assertThatThrownBy(() -> routes.gatewayRoutes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(
            routes.sameRestartFields(
                RuntimeParameters.load(RuntimeParametersTest.SHARED, "{}", "api-gateway")))
        .isFalse();
    assertThat(routes.digest()).isNotEqualTo(RuntimeParameters.defaults().digest());
    assertThatThrownBy(() -> RuntimeParameters.load(definition, "{}", "api-gateway"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RuntimeParameters.load(RuntimeParametersTest.SHARED, definition))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GatewayRouteSpec.parse(
                    Map.of("routes", java.util.Collections.nCopies(65, Map.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
