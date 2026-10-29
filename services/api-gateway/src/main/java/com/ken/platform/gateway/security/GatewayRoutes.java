package com.ken.platform.gateway.security;

import com.ken.platform.gateway.bff.BffNamespace;
import java.net.URI;
import java.util.*;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

/** 原生定义只检查安全边界、派生 BFF，不重新匹配请求路径。 */
public final class GatewayRoutes {
  private final List<RouteDefinition> definitions;

  public GatewayRoutes(GatewayProperties properties) {
    var ids = new HashSet<String>();
    var paths = new HashSet<String>();
    for (var route : properties.getRoutes()) {
      if (!route.getId().matches("[a-z][a-z0-9-]{0,79}")
          || route.getId().startsWith("framework-")
          || !ids.add(route.getId())
          || !route.getUri().toString().matches("lb://[a-z][a-z0-9-]{0,127}")
          || route.getUri().getHost().equals("api-gateway")
          || route.getOrder() != 0
          || route.getPredicates().size() != 1) throw invalid();
      var predicate = route.getPredicates().getFirst();
      if (!predicate.getName().equals("Path") || predicate.getArgs().size() != 1) throw invalid();
      String path = predicate.getArgs().values().iterator().next();
      if (!path.matches("/api/v1/[a-z][a-z0-9-]*/\\*\\*")
          || !paths.add(path)
          || path.equals("/api/v1/identity/**")) throw invalid();
      if (!Set.of("platform.audience", "platform.streaming")
              .containsAll(route.getMetadata().keySet())
          || !(route.getMetadata().get("platform.audience") instanceof String audience)
          || !audience.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
          || route.getMetadata().containsKey("platform.streaming")
              && !(route.getMetadata().get("platform.streaming") instanceof Boolean)
          || route.getFilters().size() > 1) throw invalid();
      for (var filter : route.getFilters()) validateLimiter(filter);
    }
    definitions = List.copyOf(properties.getRoutes());
  }

  private static void validateLimiter(FilterDefinition filter) {
    var args = filter.getArgs();
    if (!filter.getName().equals("RequestRateLimiter")
        || !args.keySet()
            .equals(
                Set.of(
                    "key-resolver",
                    "rate-limiter",
                    "redis-rate-limiter.replenishRate",
                    "redis-rate-limiter.burstCapacity",
                    "redis-rate-limiter.requestedTokens"))
        || !"#{@tenantOrClientKeyResolver}".equals(args.get("key-resolver"))
        || !"#{@platformRedisRateLimiter}".equals(args.get("rate-limiter"))) throw invalid();
    try {
      int replenish = Integer.parseInt(args.get("redis-rate-limiter.replenishRate"));
      int burst = Integer.parseInt(args.get("redis-rate-limiter.burstCapacity"));
      int tokens = Integer.parseInt(args.get("redis-rate-limiter.requestedTokens"));
      if (replenish < 1 || burst < 1 || tokens < 1 || tokens > burst) throw invalid();
    } catch (NumberFormatException bad) {
      throw invalid();
    }
  }

  public List<RouteDefinition> definitions() {
    return definitions;
  }

  public Set<String> audiences() {
    var result = new HashSet<String>();
    definitions.forEach(r -> result.add((String) r.getMetadata().get("platform.audience")));
    return Set.copyOf(result);
  }

  public String[] publicPaths() {
    return IdentityEndpoints.paths().toArray(String[]::new);
  }

  public List<RouteDefinition> identityRoutes() {
    List<RouteDefinition> result = new ArrayList<>();
    var resource = new RouteDefinition();
    resource.setId("framework-identity-api");
    resource.setOrder(-5);
    resource.setUri(URI.create("lb://identity-service"));
    resource.setPredicates(List.of(new PredicateDefinition("Path=/api/v1/identity/**")));
    resource.setFilters(
        List.of(
            new FilterDefinition("RemoveRequestHeader=Cookie"),
            new FilterDefinition("RemoveResponseHeader=Set-Cookie")));
    resource.setMetadata(Map.of("platform.identity-resource", true));
    result.add(resource);
    int index = 0;
    for (String path : IdentityEndpoints.paths().stream().sorted().toList()) {
      var route = new RouteDefinition();
      route.setId("framework-identity-" + index++);
      route.setOrder(-10);
      route.setUri(URI.create("lb://identity-service"));
      route.setPredicates(List.of(new PredicateDefinition("Path=" + path)));
      route.setMetadata(Map.of("platform.identity-public", true));
      result.add(route);
    }
    return List.copyOf(result);
  }

  public List<RouteDefinition> bffRoutes(Collection<BffNamespace> namespaces) {
    List<RouteDefinition> result = new ArrayList<>();
    for (var namespace : namespaces) {
      if (namespace == BffNamespace.WEB) {
        for (var original : definitions) {
          var route = new RouteDefinition();
          route.setId("framework-bff-" + original.getId());
          route.setUri(original.getUri());
          String path = original.getPredicates().getFirst().getArgs().values().iterator().next();
          route.setPredicates(List.of(new PredicateDefinition("Path=" + namespace.path() + path)));
          var filters = new ArrayList<>(original.getFilters());
          filters.add(new FilterDefinition("StripPrefix=2"));
          filters.add(new FilterDefinition("RemoveRequestHeader=Cookie"));
          filters.add(new FilterDefinition("RemoveResponseHeader=Set-Cookie"));
          route.setFilters(filters);
          var metadata = new HashMap<>(original.getMetadata());
          metadata.put("bff.namespace", namespace.name());
          metadata.put("bff.audience", metadata.get("platform.audience"));
          metadata.put("platform.logical-route", original.getId());
          route.setMetadata(metadata);
          result.add(route);
        }
      } else {
        var route = new RouteDefinition();
        route.setId("framework-bff-identity-" + namespace.name().toLowerCase(Locale.ROOT));
        route.setUri(URI.create("lb://identity-service"));
        route.setPredicates(
            List.of(new PredicateDefinition("Path=" + namespace.path() + "/api/**")));
        route.setFilters(
            List.of(
                new FilterDefinition(
                    "RewritePath="
                        + namespace.path()
                        + "/api/(?<remaining>.*), /api/v1/identity/${remaining}"),
                new FilterDefinition("RemoveRequestHeader=Cookie"),
                new FilterDefinition("RemoveResponseHeader=Set-Cookie")));
        route.setMetadata(
            Map.of(
                "bff.namespace",
                namespace.name(),
                "bff.audience",
                "identity-service",
                "platform.audience",
                "identity-service"));
        result.add(route);
      }
    }
    return List.copyOf(result);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("原生 Gateway 路由不符合安全边界，请检查 Path、audience 与限流参数");
  }
}
