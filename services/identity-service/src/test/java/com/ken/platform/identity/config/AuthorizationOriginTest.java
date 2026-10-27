package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthorizationOriginTest {
  @Test
  void issuerIsFixedAndOnlyLocalLoopbackHasHttpException() {
    var config = new AuthorizationServerConfig();
    for (String origin :
        java.util.List.of(
            "http://localhost:18080", "http://127.0.0.1:18080/", "http://[::1]:18080")) {
      assertThat(
              config
                  .authorizationServerSettings(
                      new MockEnvironment()
                          .withProperty("APP_ENV", "local")
                          .withProperty("PUBLIC_ORIGIN", origin))
                  .getIssuer())
          .isEqualTo(origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin);
    }
    assertThat(
            config
                .authorizationServerSettings(
                    new MockEnvironment()
                        .withProperty("APP_ENV", "prod")
                        .withProperty("PUBLIC_ORIGIN", "https://login.example"))
                .getIssuer())
        .isEqualTo("https://login.example");
  }

  @Test
  void malformedOrUntrustedOriginsAndUnknownEnvironmentPreventStartup() {
    var config = new AuthorizationServerConfig();
    for (String origin :
        java.util.List.of(
            "http://login.example",
            "http://localhost.evil",
            "https://user:secret@login.example",
            "https://login.example/callback",
            "https://login.example?x=y",
            "https://login.example#x",
            "https://login.example:99999")) {
      assertThatThrownBy(
              () ->
                  config.authorizationServerSettings(
                      new MockEnvironment()
                          .withProperty("APP_ENV", "local")
                          .withProperty("PUBLIC_ORIGIN", origin)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                config.authorizationServerSettings(
                    new MockEnvironment()
                        .withProperty("APP_ENV", "prod")
                        .withProperty("PUBLIC_ORIGIN", "http://localhost")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                config.authorizationServerSettings(
                    new MockEnvironment()
                        .withProperty("APP_ENV", "unknown")
                        .withProperty("PUBLIC_ORIGIN", "https://login.example")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
