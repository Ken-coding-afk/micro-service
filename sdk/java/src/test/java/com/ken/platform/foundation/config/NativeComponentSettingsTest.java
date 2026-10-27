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

class NativeComponentSettingsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static Stream<JsonNode> cases() throws Exception {
    var document =
        JSON.readTree(Files.readString(Path.of("../../tests/fixtures/gateway-routes-v1.json")));
    assertThat(document.get("test_only").asBoolean()).isTrue();
    return document.get("cases").valueStream();
  }

  @ParameterizedTest
  @MethodSource("cases")
  void sharedRouteContract(JsonNode fixture) {
    String service = JSON.writeValueAsString(Map.of("gateway", fixture.get("block")));
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
        "spring: {cloud: {gateway: {server: {webflux: {routes: [{id: documents, uri: 'lb://document-service', predicates: ['Path=/api/v1/documents/**'], metadata: {platform.audience: documents}}]}}}}}";
    var routes = RuntimeParameters.load(RuntimeParametersTest.SHARED, definition, "api-gateway");
    assertThat(routes.spring()).isNotEmpty();
    assertThatThrownBy(() -> routes.spring().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(routes.sameRestartFields(RuntimeParameters.defaults())).isFalse();
    assertThatThrownBy(() -> RuntimeParameters.load(definition, "{}", "api-gateway"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RuntimeParameters.load(RuntimeParametersTest.SHARED, definition, "other-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
