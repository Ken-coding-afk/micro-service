package com.ken.platform.gateway;

import com.ken.platform.foundation.config.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.gateway.config.GatewayProperties;

public final class RouteFixtures {
  private RouteFixtures() {}

  public static RuntimeParameters parameters(String... routes) {
    return RuntimeParameters.load(
        "log_level: INFO",
        "spring: {cloud: {gateway: {server: {webflux: {routes: ["
            + String.join(",", routes)
            + "]}}}}}",
        "api-gateway");
  }

  public static String route(String id, String path, String service, String audience) {
    return "{id: "
        + id
        + ", uri: 'lb://"
        + service
        + "', predicates: ['Path="
        + path
        + "'], metadata: {platform.audience: "
        + audience
        + "}}";
  }

  public static GatewayProperties properties(RuntimeParameters parameters) {
    return new Binder(
            new MapConfigurationPropertySource(
                NativeComponentSettings.properties(parameters.spring())))
        .bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class))
        .orElseGet(GatewayProperties::new);
  }
}
