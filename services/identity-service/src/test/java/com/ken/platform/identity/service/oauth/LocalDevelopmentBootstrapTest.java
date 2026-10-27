package com.ken.platform.identity.service.oauth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ken.platform.foundation.http.PlatformException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalDevelopmentBootstrapTest {
  private LocalDevelopmentBootstrap.Input input(String environment, String mode, String database) {
    String secret = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    return new LocalDevelopmentBootstrap.Input(
        "equipment-platform",
        environment,
        "http://localhost:18080",
        database,
        mode,
        "a".repeat(64),
        List.of(
            new LocalDevelopmentBootstrap.Service("equipment-service", secret, secret),
            new LocalDevelopmentBootstrap.Service("inspection-service", secret, secret)),
        Map.of(
            "alpha:admin",
            secret,
            "alpha:writer",
            secret,
            "alpha:denied",
            secret,
            "beta:admin",
            secret,
            "beta:writer",
            secret,
            "beta:denied",
            secret),
        secret);
  }

  @Test
  void acceptsOnlyBoundedLocalProjectInput() {
    assertDoesNotThrow(
        () ->
            LocalDevelopmentBootstrap.validate(
                input("local", "self_hosted", "equipment_platform_local_identity")));
    assertThrows(
        PlatformException.class,
        () ->
            LocalDevelopmentBootstrap.validate(
                input("prod", "self_hosted", "equipment_platform_local_identity")));
    assertThrows(
        PlatformException.class,
        () ->
            LocalDevelopmentBootstrap.validate(
                input("local", "external", "equipment_platform_local_identity")));
    assertThrows(
        PlatformException.class,
        () ->
            LocalDevelopmentBootstrap.validate(
                input("local", "self_hosted", "other_project_local_identity")));
  }
}
