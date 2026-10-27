package com.ken.platform.foundation.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只允许指定组件树；绑定仍交给 Spring 原生属性对象，不建立路由字段别名。 */
public final class NativeComponentSettings {
  private NativeComponentSettings() {}

  public static Map<String, Object> parse(Object source, String service) {
    if (source == null) return Map.of();
    var root = mapping(source);
    exact(root, "cloud");
    var cloud = mapping(root.get("cloud"));
    if ("api-gateway".equals(service)) {
      exact(cloud, "gateway");
      var gateway = mapping(cloud.get("gateway"));
      exact(gateway, "server");
      var server = mapping(gateway.get("server"));
      exact(server, "webflux");
      var webflux = mapping(server.get("webflux"));
      exact(webflux, "routes");
      if (!(webflux.get("routes") instanceof List<?> routes) || routes.size() > 64) throw invalid();
      for (Object value : routes) {
        if (!(value instanceof Map<?, ?> route)
            || !Set.of("id", "uri", "predicates", "filters", "metadata")
                .containsAll(route.keySet())) throw invalid();
      }
    } else {
      exact(cloud, "sentinel");
      var sentinel = mapping(cloud.get("sentinel"));
      exact(sentinel, "enabled");
      if (!(sentinel.get("enabled") instanceof Boolean)) throw invalid();
    }
    return freeze(root, 0);
  }

  public static boolean sentinelEnabled(Map<String, Object> spring) {
    if (!(spring.get("cloud") instanceof Map<?, ?> cloud)
        || !(cloud.get("sentinel") instanceof Map<?, ?> sentinel)) return false;
    return Boolean.TRUE.equals(sentinel.get("enabled"));
  }

  public static Map<String, Object> properties(Map<String, Object> spring) {
    var result = new LinkedHashMap<String, Object>();
    flatten(spring, "spring", result);
    return result;
  }

  private static void flatten(Object value, String prefix, Map<String, Object> result) {
    if (value instanceof Map<?, ?> map) {
      map.forEach(
          (key, child) ->
              flatten(
                  child,
                  prefix
                      + (prefix.endsWith(".metadata") || prefix.endsWith(".args")
                          ? "[" + key + "]"
                          : "." + key),
                  result));
    } else if (value instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) flatten(list.get(i), prefix + "[" + i + "]", result);
    } else result.put(prefix, value);
  }

  private static Map<String, Object> freeze(Map<?, ?> value, int depth) {
    if (depth > 20) throw invalid();
    var result = new LinkedHashMap<String, Object>();
    value.forEach(
        (key, item) -> {
          if (!(key instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9._-]{0,159}"))
            throw invalid();
          result.put(name, freezeValue(item, depth + 1));
        });
    return Collections.unmodifiableMap(result);
  }

  private static Object freezeValue(Object value, int depth) {
    if (depth > 20) throw invalid();
    if (value instanceof Map<?, ?> map) return freeze(map, depth);
    if (value instanceof List<?> list) {
      if (list.size() > 128) throw invalid();
      return list.stream().map(item -> freezeValue(item, depth + 1)).toList();
    }
    if (value instanceof String || value instanceof Boolean || value instanceof Number)
      return value;
    throw invalid();
  }

  private static Map<?, ?> mapping(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    return map;
  }

  private static void exact(Map<?, ?> map, String field) {
    if (!map.keySet().equals(Set.of(field))) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("组件配置只接受所属服务的原生路由或 Sentinel enabled 字段");
  }
}
