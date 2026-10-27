package com.ken.platform.foundation.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RuntimeParametersTest {
  @Test
  void businessParametersAreServiceOwnedImmutableAndRequireRestart() {
    var value =
        RuntimeParameters.load(
            SHARED, "business: {max_page_size: 50, labels: [one]}", "sample-service");
    assertThat(value.business()).containsEntry("max_page_size", 50);
    assertThatThrownBy(() -> value.business().put("another", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(value.sameRestartFields(RuntimeParameters.load(SHARED, "{}", "sample-service")))
        .isFalse();
    for (String invalid :
        new String[] {
          "business: null",
          "business: []",
          "business: {pg_host: other}",
          "business: {models: [{api_key: private}]}"
        }) {
      assertThatThrownBy(() -> RuntimeParameters.load(SHARED, invalid, "sample-service"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> RuntimeParameters.load(SHARED + "\nbusiness: {}", "{}", "sample-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void identityBrandBelongsOnlyToIdentityAndRequiresRestartWithoutChangingDefaultDigests() {
    var branded =
        RuntimeParameters.load(
            SHARED,
            "project_display_name: 文档智能平台\nlogo_url: /identity/admin/assets/project-logo.svg",
            "identity-service");
    assertThat(branded.identityAdminBrand().projectDisplayName()).isEqualTo("文档智能平台");
    assertThat(branded.identityAdminBrand().logoUrl())
        .isEqualTo("/identity/admin/assets/project-logo.svg");
    var original = RuntimeParameters.load(SHARED, "{}", "identity-service");
    assertThat(branded.sameRestartFields(original)).isFalse();
    assertThat(branded.digest()).isNotEqualTo(original.digest());
    assertThat(original.digest())
        .isEqualTo(RuntimeParameters.load(SHARED, "{}", "document-service").digest());
    assertThatThrownBy(
            () -> RuntimeParameters.load(SHARED, "project_display_name: 其他", "document-service"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RuntimeParameters.load(
                    SHARED + "project_display_name: 共享", "{}", "identity-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "project_display_name: '<DO_NOT_ECHO_BRAND>'",
        "project_display_name: true",
        "project_display_name: null",
        "project_display_name: '   '",
        "logo_url: null",
        "logo_url: https://untrusted.example/logo.svg",
        "logo_url: //untrusted.example/logo.svg",
        "logo_url: 'data:image/svg+xml,DO_NOT_ECHO_BRAND'",
        "logo_url: /a/../logo.svg",
        "logo_url: /a/%2e%2e/logo.svg",
        "logo_url: '/logo.svg?credential=DO_NOT_ECHO_BRAND'",
        "logo_url: /logo%0d%0a.svg",
        "logo_url: /logo%5cother.svg",
        "logo_url: relative.svg"
      })
  void rejectsBrandScriptsExternalPathsAndInvalidValuesWithoutEcho(String value) {
    assertThatThrownBy(() -> RuntimeParameters.load(SHARED, value, "identity-service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("DO_NOT_ECHO_BRAND");
  }

  public static final String SHARED =
      """
      allow_override: [log_level]
      log_level: INFO
      http:
        connect_timeout_seconds: 2
        response_budget_seconds: 10
        get_retry_extra: 1
      observation:
        metrics_enabled: true
      """;

  @Test
  void validatesWholeSnapshotAndOnlyDeclaredOverrides() {
    var parameters = RuntimeParameters.load(SHARED, "log_level: DEBUG");
    assertThat(parameters.logLevel()).isEqualTo("DEBUG");
    assertThat(parameters.connectTimeoutSeconds()).isEqualTo(2);
    assertThat(parameters.digest())
        .isEqualTo(RuntimeParameters.load(SHARED, "log_level: DEBUG").digest());
    assertThat(parameters.sameRestartFields(RuntimeParameters.load(SHARED, "{}"))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http: {response_budget_seconds: 999}",
        "PG_HOST: other",
        "client_secret: never-print-this",
        "client_secret: {}",
        "http.connect_timeout_seconds: 2",
        "observation: {metrics_enabled: 'yes'}",
        "log_level: INFO\nlog_level: DEBUG",
        "log_level: [INFO]",
        "[]",
        ""
      })
  void rejectsBadSnapshotWithoutEchoingSecret(String service) {
    assertThatThrownBy(() -> RuntimeParameters.load(SHARED, service))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("never-print-this");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "connect_timeout_seconds: 0",
        "response_budget_seconds: -1",
        "get_retry_extra: 2",
        "get_retry_extra: true",
        "get_retry_extra: 0.5",
        "get_retry_extra: 1.0",
        "response_budget_seconds: '10'"
      })
  void rejectsInvalidScalarAndRange(String http) {
    assertThatThrownBy(() -> RuntimeParameters.load("http: {" + http + "}", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
