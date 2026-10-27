package com.ken.platform.gateway;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.gateway.security.GatewayIntrospectionClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class GatewayIntrospectionClientsTest {
  @TempDir Path directory;

  private RuntimeParameters parameters() {
    return RouteFixtures.parameters(
        RouteFixtures.route("documents", "/api/v1/documents/**", "document-service", "documents"),
        RouteFixtures.route(
            "analysis", "/api/v1/analysis-jobs/**", "analysis-service", "analysis"));
  }

  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("APP_ENV", "local")
        .withProperty("PUBLIC_ORIGIN", "http://localhost:18080");
  }

  private GatewayIntrospectionClients create(
      MockEnvironment environment, RuntimeParameters parameters) {
    return new GatewayIntrospectionClients(
        environment,
        parameters,
        new com.ken.platform.gateway.security.GatewayRoutes(RouteFixtures.properties(parameters)),
        new ServiceDiscovery(System::nanoTime),
        Clock.systemUTC());
  }

  private List<Map<String, Object>> readers() throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    for (String audience : List.of("documents", "analysis")) {
      Path secret = directory.resolve(audience + ".password");
      Files.writeString(secret, "DO_NOT_ECHO_GATEWAY_SECRET");
      result.add(
          new HashMap<>(
              Map.of(
                  "audience",
                  audience,
                  "client_id",
                  "gateway-" + audience,
                  "secret_file",
                  secret.toString())));
    }
    return result;
  }

  private MockEnvironment manifest(String text) throws Exception {
    Path file = directory.resolve("readers.json");
    Files.writeString(file, text);
    return environment().withProperty("GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE", file.toString());
  }

  @Test
  void routeAudiencesRequireExactlyTheirOwnIndependentReadersAndSafeOutput() throws Exception {
    var environment =
        manifest(JsonMapper.builder().build().writeValueAsString(Map.of("readers", readers())));
    try (var clients = create(environment, parameters())) {
      assertThat(clients.empty()).isFalse();
      assertThat(clients.forAudience("documents")).isNotSameAs(clients.forAudience("analysis"));
      assertThat(clients.toString())
          .doesNotContain("DO_NOT_ECHO", directory.toString(), ".password");
      assertThatThrownBy(() -> clients.forAudience("not-registered"))
          .isInstanceOf(
              org.springframework.security.authentication.AuthenticationServiceException.class);
    }
    assertThatThrownBy(() -> create(environment, RuntimeParameters.defaults()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingManifestFailsOnlyWhenProtectedRoutesExist() {
    assertThatThrownBy(() -> create(environment(), parameters()))
        .hasMessage("Gateway 独立内省凭据清单缺失或不合法");
    try (var clients = create(environment(), RuntimeParameters.defaults())) {
      assertThat(clients.empty()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "duplicate-audience",
        "duplicate-client",
        "foreign-audience",
        "raw-secret",
        "relative-secret",
        "duplicate-json",
        "unknown-root",
        "malformed"
      })
  void malformedOrBroaderReaderManifestFailsClosedWithoutEcho(String kind) throws Exception {
    var readers = readers();
    switch (kind) {
      case "missing" -> readers.removeLast();
      case "duplicate-audience" -> readers.getLast().put("audience", "documents");
      case "duplicate-client" ->
          readers.getLast().put("client_id", readers.getFirst().get("client_id"));
      case "foreign-audience" -> readers.getLast().put("audience", "unrelated");
      case "raw-secret" -> readers.getFirst().put("secret", "DO_NOT_ECHO_GATEWAY_SECRET");
      case "relative-secret" -> readers.getFirst().put("secret_file", "relative.password");
    }
    String text = JsonMapper.builder().build().writeValueAsString(Map.of("readers", readers));
    if (kind.equals("duplicate-json"))
      text =
          "{\"readers\":[],\"readers\":"
              + JsonMapper.builder().build().writeValueAsString(readers)
              + "}";
    if (kind.equals("unknown-root")) text = "{\"clients\":[]}";
    if (kind.equals("malformed")) text = "{DO_NOT_ECHO_GATEWAY_SECRET";
    var environment = manifest(text);
    assertThatThrownBy(() -> create(environment, parameters()))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasNoCause()
        .hasMessage("Gateway 独立内省凭据清单缺失或不合法");
  }
}
