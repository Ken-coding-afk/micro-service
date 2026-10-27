package com.ken.platform.gateway.security;

import com.ken.platform.foundation.config.GatewayRouteSpec;
import com.ken.platform.foundation.config.RuntimeParameters;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 启动快照同时供路由与认证选择目标；不从请求参数选择 audience 或凭据。 */
public final class GatewayRoutes {
  private record Entry(GatewayRouteSpec definition, PathPattern pattern) {}

  private final List<Entry> entries;

  public GatewayRoutes(RuntimeParameters parameters) {
    entries =
        parameters.gatewayRoutes().stream()
            .map(route -> new Entry(route, PathPatternParser.defaultInstance.parse(route.path())))
            .toList();
  }

  public GatewayRouteSpec api(PathContainer path) {
    return entries.stream()
        .filter(entry -> entry.definition().api() && entry.pattern().matches(path))
        .map(Entry::definition)
        .findFirst()
        .orElse(null);
  }

  public String[] publicPaths() {
    return entries.stream()
        .filter(entry -> !entry.definition().api())
        .map(entry -> entry.definition().path())
        .toArray(String[]::new);
  }
}
