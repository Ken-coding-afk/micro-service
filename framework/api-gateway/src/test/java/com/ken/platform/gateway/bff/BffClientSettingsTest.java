package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class BffClientSettingsTest {
  @TempDir Path directory;
  private static final String SECRET = "configuration-test-only-secret-with-32-characters";

  private StandardEnvironment environment(Map<String, Object> document) throws Exception {
    Path secret = directory.resolve("client.secret"), config = directory.resolve("bff.json");
    Files.writeString(secret, SECRET);
    Map<String, Object> fields = new HashMap<>();
    fields.put("platform_secret_file", secret.toString());
    fields.put("tenant_secret_file", secret.toString());
    fields.putAll(document);
    Files.writeString(config, JsonMapper.builder().build().writeValueAsString(fields));
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                new HashMap<>(
                    Map.of(
                        "APP_ENV",
                        "local",
                        "PUBLIC_ORIGIN",
                        "http://localhost:18080",
                        "GATEWAY_BFF_CLIENTS_SECRET_FILE",
                        config.toString()))));
    return env;
  }

  private String parameter(String uri, String key) {
    return java.util.Arrays.stream(URI.create(uri).getRawQuery().split("&"))
        .map(item -> item.split("=", 2))
        .filter(pair -> pair[0].equals(key))
        .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
        .findFirst()
        .orElse(null);
  }

  @Test
  void managementClientsHaveFixedNamespacesCallbacksAndSeparateRequestedScopes() throws Exception {
    var settings = new BffClientSettings(environment(Map.of()));
    assertThat(settings.namespaces())
        .containsExactlyInAnyOrder(BffNamespace.PLATFORM, BffNamespace.TENANT);
    assertThat(settings.secure()).isFalse();
    var platform = settings.client(BffNamespace.PLATFORM);
    assertThat(platform.getClientId()).isEqualTo("identity-admin-platform");
    assertThat(platform.getRedirectUri())
        .isEqualTo("http://localhost:18080/bff/identity/platform/callback");
    assertThat(platform.getScopes())
        .contains("profile", "platform.tenants")
        .noneMatch(scope -> scope.startsWith("identity."));
    assertThat(settings.client(BffNamespace.TENANT).getScopes())
        .contains("identity.users", "identity.audit")
        .noneMatch(scope -> scope.startsWith("platform."));
    assertThat(settings.toString()).doesNotContain(SECRET);
    assertThatThrownBy(() -> settings.client(BffNamespace.WEB))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void optionalWebClientIsFixedAndCannotDeclareAllowedTenantsOrClientId() throws Exception {
    Path secret = directory.resolve("web.secret");
    Files.writeString(secret, SECRET);
    var web =
        Map.<String, Object>of(
            "secret_file",
            secret.toString(),
            "requested_scopes",
            java.util.List.of("profile", "documents.read"));
    var settings = new BffClientSettings(environment(Map.of("web", web)));
    assertThat(settings.client(BffNamespace.WEB).getClientId()).isEqualTo("web-bff");
    assertThat(settings.client(BffNamespace.WEB).getScopes())
        .containsExactlyInAnyOrder("profile", "documents.read");
    var invalid = new HashMap<>(web);
    invalid.put("allowed_tenant_ids", java.util.List.of("another-tenant"));
    assertThatThrownBy(() -> new BffClientSettings(environment(Map.of("web", invalid))))
        .isInstanceOf(IllegalArgumentException.class);
    invalid.remove("allowed_tenant_ids");
    invalid.put("client_id", "identity-admin-platform");
    assertThatThrownBy(() -> new BffClientSettings(environment(Map.of("web", invalid))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void publicOriginAndSecretDescriptorRejectUnsafeTransportInlineSecretsAndDuplicateKeys()
      throws Exception {
    var env = environment(Map.of());
    var values = (MapPropertySource) env.getPropertySources().get("test");
    for (String origin :
        java.util.List.of(
            "http://public.example",
            "https://user@public.example",
            "https://public.example/path",
            "https://public.example?q=x")) {
      values.getSource().put("PUBLIC_ORIGIN", origin);
      assertThatThrownBy(() -> new BffClientSettings(env))
          .isInstanceOf(IllegalArgumentException.class);
    }
    values.getSource().put("PUBLIC_ORIGIN", "http://localhost:18080");
    values.getSource().put("APP_ENV", "prod");
    assertThatThrownBy(() -> new BffClientSettings(env))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BffClientSettings(environment(Map.of("tenant_secret", SECRET))))
        .isInstanceOf(IllegalArgumentException.class);
    var duplicate = environment(Map.of());
    Files.writeString(
        directory.resolve("bff.json"),
        "{\"platform_secret_file\":\"first\",\"platform_secret_file\":\"second\"}");
    assertThatThrownBy(() -> new BffClientSettings(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tenantLoginUsesOfficialFreshStateS256AndRealQueryParametersWithoutClientOverride()
      throws Exception {
    var settings = new BffClientSettings(environment(Map.of()));
    var resolver = new BffAuthorizationRequests(settings, settings.repository());
    var request =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("http://localhost:18080/bff/identity/tenant/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("tenant_code=tenant-a"));
    var result = resolver.resolve(request).block();
    assertThat(result).isNotNull();
    assertThat(parameter(result.getAuthorizationRequestUri(), "client_id"))
        .isEqualTo("identity-admin-tenant");
    assertThat(parameter(result.getAuthorizationRequestUri(), "tenant_code")).isEqualTo("tenant-a");
    assertThat(parameter(result.getAuthorizationRequestUri(), "prompt")).isEqualTo("login");
    assertThat(parameter(result.getAuthorizationRequestUri(), "code_challenge_method"))
        .isEqualTo("S256");
    String verifier = result.getAttribute("code_verifier");
    assertThat(verifier.length()).isGreaterThanOrEqualTo(43);
    assertThat(parameter(result.getAuthorizationRequestUri(), "code_verifier")).isNull();
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    assertThat(challenge.equals(parameter(result.getAuthorizationRequestUri(), "code_challenge")))
        .isTrue();
    assertThat(result.getState().length()).isGreaterThanOrEqualTo(32);
    assertThat(parameter(result.getAuthorizationRequestUri(), "redirect_uri"))
        .isEqualTo(settings.client(BffNamespace.TENANT).getRedirectUri());
  }

  @Test
  void concurrentLoginSelectionsStaySeparateAndOnlyTrustedPathSelectsClient() throws Exception {
    var settings = new BffClientSettings(environment(Map.of()));
    var resolver = new BffAuthorizationRequests(settings, settings.repository());
    var first =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("http://localhost:18080/bff/identity/tenant/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("tenant_code=tenant-a"));
    var second =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("http://localhost:18080/bff/identity/tenant/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("tenant_code=tenant-b"));
    var both = Mono.zip(resolver.resolve(first, "platform"), resolver.resolve(second)).block();
    assertThat(both).isNotNull();
    assertThat(parameter(both.getT1().getAuthorizationRequestUri(), "tenant_code"))
        .isEqualTo("tenant-a");
    assertThat(parameter(both.getT2().getAuthorizationRequestUri(), "tenant_code"))
        .isEqualTo("tenant-b");
    assertThat(both.getT1().getClientId()).isEqualTo("identity-admin-tenant");
    assertThat(both.getT1().getState().equals(both.getT2().getState())).isFalse();
    assertThat(
            resolver
                .resolve(
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                            "http://localhost:18080/bff/identity/tenant/login")))
                .block())
        .isNull();
  }

  @Test
  void loginRejectsPasswordsUnknownFieldsRepeatedTenantAndPlatformTenantSelection()
      throws Exception {
    var settings = new BffClientSettings(environment(Map.of()));
    var resolver = new BffAuthorizationRequests(settings, settings.repository());
    for (String body :
        java.util.List.of(
            "tenant_code=a&tenant_code=b",
            "tenant_code=a&password=DO_NOT_ECHO",
            "tenant_code=a&client_id=identity-admin-platform",
            "tenant_id=other",
            "tenant_code=../bad")) {
      var request =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("http://localhost:18080/bff/identity/tenant/login")
                  .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                  .body(body));
      assertThatThrownBy(() -> resolver.resolve(request).block())
          .isInstanceOf(PlatformException.class);
    }
    var platform =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("http://localhost:18080/bff/identity/platform/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("tenant_code=tenant-a"));
    assertThatThrownBy(() -> resolver.resolve(platform).block())
        .isInstanceOf(PlatformException.class);
  }
}
