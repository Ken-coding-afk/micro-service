package com.ken.platform.foundation.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 版本化显式路由：只允许固定 API 前缀与身份协议/页面入口，不接受任意 URI/过滤脚本。 */
public record GatewayRouteSpec(String id, String path, String service, String audience) {
  private static final Set<String> IDENTITY_PATHS =
      Set.of(
          "/.well-known/openid-configuration",
          "/.well-known/oauth-authorization-server",
          "/oauth2/authorize",
          "/oauth2/token",
          "/oauth2/introspect",
          "/oauth2/revoke",
          "/oauth2/jwks",
          "/userinfo",
          "/connect/logout",
          "/identity/login",
          "/identity/password/initial",
          "/identity/session",
          "/identity/logout",
          "/identity/external",
          "/identity/external/**",
          "/oauth2/authorization/*",
          "/login/oauth2/code/*",
          "/identity/admin/**",
          "/api/v1/identity/admin-config");

  public GatewayRouteSpec {
    if (id == null
        || !id.matches("[a-z][a-z0-9-]{0,79}")
        || service == null
        || !service.matches("[a-z][a-z0-9-]{0,127}")
        || service.equals("api-gateway")
        || path == null) throw invalid();
    boolean api = path.matches("/api/v1/[a-z][a-z0-9-]*/\\*\\*");
    if (api) {
      if (audience == null || !audience.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))
        throw invalid();
      if (path.equals("/api/v1/identity/**") && !service.equals("identity-service"))
        throw invalid();
    } else if (!IDENTITY_PATHS.contains(path)
        || !service.equals("identity-service")
        || audience != null) throw invalid();
  }

  public boolean api() {
    return audience != null;
  }

  /** 框架固定身份协议/页面白名单；BFF 与显式配置共用同一入口合同。 */
  public static Set<String> identityPaths() {
    return IDENTITY_PATHS;
  }

  public static List<GatewayRouteSpec> parse(Object block) {
    if (!(block instanceof Map<?, ?> gateway)
        || !gateway.keySet().equals(Set.of("routes"))
        || !(gateway.get("routes") instanceof List<?> routes)
        || routes.size() > 64) throw invalid();
    var result = new ArrayList<GatewayRouteSpec>();
    var ids = new HashSet<String>();
    var paths = new HashSet<String>();
    for (Object item : routes) {
      if (!(item instanceof Map<?, ?> route)
          || !Set.of("id", "path", "service", "audience").containsAll(route.keySet())
          || !(route.get("id") instanceof String id)
          || !(route.get("path") instanceof String path)
          || !(route.get("service") instanceof String service)
          || route.containsKey("audience") && !(route.get("audience") instanceof String))
        throw invalid();
      var definition = new GatewayRouteSpec(id, path, service, (String) route.get("audience"));
      if (!ids.add(id) || !paths.add(path)) throw invalid();
      result.add(definition);
    }
    return List.copyOf(result);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Gateway 显式路由字段、目标或路径不合法");
  }
}
