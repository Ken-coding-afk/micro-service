package com.ken.platform.foundation.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 运行参数固定合同；不接收环境值、秘密或 Spring 任意属性。 */
public record RuntimeParameters(
    String logLevel,
    double connectTimeoutSeconds,
    double responseBudgetSeconds,
    int getRetryExtra,
    boolean metricsEnabled,
    List<GatewayRouteSpec> gatewayRoutes,
    IdentityAdminBrand identityAdminBrand,
    Map<String, Object> business) {
  private static final Set<String> FIELDS =
      Set.of(
          "log_level",
          "http.connect_timeout_seconds",
          "http.response_budget_seconds",
          "http.get_retry_extra",
          "observation.metrics_enabled");

  public RuntimeParameters {
    gatewayRoutes = List.copyOf(gatewayRoutes);
    business = freezeBusiness(business);
    if (identityAdminBrand == null
        || logLevel == null
        || !Set.of("DEBUG", "INFO", "WARN", "WARNING", "ERROR").contains(logLevel)
        || !Double.isFinite(connectTimeoutSeconds)
        || connectTimeoutSeconds <= 0
        || connectTimeoutSeconds > 10
        || !Double.isFinite(responseBudgetSeconds)
        || responseBudgetSeconds <= 0
        || responseBudgetSeconds > 300
        || getRetryExtra < 0
        || getRetryExtra > 1) {
      throw new IllegalArgumentException("运行参数类型或范围不合法");
    }
  }

  public RuntimeParameters(
      String logLevel,
      double connectTimeoutSeconds,
      double responseBudgetSeconds,
      int getRetryExtra,
      boolean metricsEnabled,
      List<GatewayRouteSpec> gatewayRoutes,
      IdentityAdminBrand identityAdminBrand) {
    this(
        logLevel,
        connectTimeoutSeconds,
        responseBudgetSeconds,
        getRetryExtra,
        metricsEnabled,
        gatewayRoutes,
        identityAdminBrand,
        Map.of());
  }

  private static Map<String, Object> freezeBusiness(Map<?, ?> source) {
    Map<String, Object> values = new TreeMap<>();
    source.forEach(
        (key, value) -> {
          String field = requireKey(key);
          if (field.matches(".*(^|_)(password|secret|token|api_key|private_key)($|_).*")
              || Set.of(
                      "project_code",
                      "app_env",
                      "public_origin",
                      "service_name",
                      "pg_host",
                      "pg_port",
                      "db_name",
                      "db_user",
                      "redis_host",
                      "redis_port",
                      "nacos_server_addr",
                      "nacos_namespace")
                  .contains(field)) throw new IllegalArgumentException("业务运行配置不能携带秘密或环境绑定");
          values.put(field, freezeValue(value));
        });
    return java.util.Collections.unmodifiableMap(values);
  }

  private static Object freezeValue(Object value) {
    if (value instanceof Map<?, ?> mapping) return freezeBusiness(mapping);
    if (value instanceof List<?> list)
      return list.stream().map(RuntimeParameters::freezeValue).toList();
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Number) return value;
    throw new IllegalArgumentException("业务配置仅支持 JSON 值");
  }

  public RuntimeParameters(
      String logLevel,
      double connectTimeoutSeconds,
      double responseBudgetSeconds,
      int getRetryExtra,
      boolean metricsEnabled,
      List<GatewayRouteSpec> gatewayRoutes) {
    this(
        logLevel,
        connectTimeoutSeconds,
        responseBudgetSeconds,
        getRetryExtra,
        metricsEnabled,
        gatewayRoutes,
        IdentityAdminBrand.defaults());
  }

  public RuntimeParameters(
      String logLevel,
      double connectTimeoutSeconds,
      double responseBudgetSeconds,
      int getRetryExtra,
      boolean metricsEnabled) {
    this(
        logLevel,
        connectTimeoutSeconds,
        responseBudgetSeconds,
        getRetryExtra,
        metricsEnabled,
        List.of());
  }

  public static RuntimeParameters defaults() {
    return new RuntimeParameters("INFO", 2, 10, 1, true);
  }

  public static RuntimeParameters load(String shared, String service) {
    return load(shared, service, null);
  }

  public static RuntimeParameters load(String shared, String service, String serviceName) {
    Map<String, Object> common = parse(shared);
    Map<String, Object> specific = parse(service);
    if (common.containsKey("business")) throw new IllegalArgumentException("共享配置不承载业务字段");
    boolean hasBusiness = specific.containsKey("business");
    Object businessValue = specific.remove("business");
    if (hasBusiness && !(businessValue instanceof Map<?, ?>))
      throw new IllegalArgumentException("business 必须是服务配置 mapping");
    Map<String, Object> business =
        businessValue == null ? Map.of() : freezeBusiness((Map<?, ?>) businessValue);
    var brand = IdentityAdminBrand.take(specific, serviceName);
    List<GatewayRouteSpec> routes = List.of();
    if (specific.containsKey("gateway") && "api-gateway".equals(serviceName))
      routes = GatewayRouteSpec.parse(specific.remove("gateway"));
    Object override = common.remove("allow_override");
    List<?> allowed = override == null ? List.of() : requireList(override);
    Map<String, Object> merged = flatten(common, "");
    if (!FIELDS.containsAll(merged.keySet()) || !merged.keySet().containsAll(allowed)) {
      throw new IllegalArgumentException("共享配置包含未知字段或错误覆盖声明");
    }
    for (var entry : flatten(specific, "").entrySet()) {
      if (!FIELDS.contains(entry.getKey())
          || merged.containsKey(entry.getKey()) && !allowed.contains(entry.getKey())) {
        throw new IllegalArgumentException("服务配置包含未知字段或越权覆盖");
      }
      merged.put(entry.getKey(), entry.getValue());
    }
    Object level = merged.getOrDefault("log_level", "INFO");
    Object metrics = merged.getOrDefault("observation.metrics_enabled", true);
    Object retry = merged.getOrDefault("http.get_retry_extra", 1);
    if (!(level instanceof String)
        || !(metrics instanceof Boolean)
        || !(retry instanceof Integer || retry instanceof Long)) {
      throw new IllegalArgumentException("运行参数类型不合法");
    }
    return new RuntimeParameters(
        (String) level,
        number(merged, "http.connect_timeout_seconds", 2),
        number(merged, "http.response_budget_seconds", 10),
        ((Number) retry).intValue(),
        (Boolean) metrics,
        routes,
        brand,
        business);
  }

  private static List<?> requireList(Object value) {
    if (!(value instanceof List<?> list)
        || list.stream().anyMatch(item -> !(item instanceof String))) {
      throw new IllegalArgumentException("allow_override 必须是字段名数组");
    }
    return list;
  }

  private static double number(Map<String, Object> values, String key, double fallback) {
    Object value = values.getOrDefault(key, fallback);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("运行参数必须为数值");
    }
    return number.doubleValue();
  }

  private static Map<String, Object> parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("必需配置缺失");
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object parsed;
    try {
      parsed = new Yaml(new SafeConstructor(options)).load(text);
    } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
      throw new IllegalArgumentException("运行配置 YAML 不合法");
    }
    if (!(parsed instanceof Map<?, ?> mapping)) {
      throw new IllegalArgumentException("配置必须为 mapping");
    }
    Map<String, Object> result = new HashMap<>();
    mapping.forEach((key, value) -> result.put(requireKey(key), value));
    return result;
  }

  private static String requireKey(Object key) {
    if (!(key instanceof String name) || !name.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("配置字段名必须为字符串");
    }
    return name;
  }

  private static Map<String, Object> flatten(Map<?, ?> source, String prefix) {
    Map<String, Object> result = new HashMap<>();
    source.forEach(
        (key, value) -> {
          String name = prefix.isEmpty() ? requireKey(key) : prefix + "." + requireKey(key);
          if (value instanceof Map<?, ?> nested) {
            if (!Set.of("http", "observation").contains(name)) {
              throw new IllegalArgumentException("运行配置包含未知结构");
            }
            result.putAll(flatten(nested, name));
          } else {
            result.put(name, value);
          }
        });
    return result;
  }

  public String digest() {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("log_level", logLevel);
    canonical.put("connect_timeout_seconds", connectTimeoutSeconds);
    canonical.put("response_budget_seconds", responseBudgetSeconds);
    canonical.put("get_retry_extra", getRetryExtra);
    canonical.put("metrics_enabled", metricsEnabled);
    canonical.put("gateway_routes", gatewayRoutes);
    if (!business.isEmpty()) canonical.put("business", business);
    if (!identityAdminBrand.equals(IdentityAdminBrand.defaults()))
      canonical.put("identity_admin_brand", identityAdminBrand);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }

  public boolean sameRestartFields(RuntimeParameters other) {
    return connectTimeoutSeconds == other.connectTimeoutSeconds
        && responseBudgetSeconds == other.responseBudgetSeconds
        && getRetryExtra == other.getRetryExtra
        && gatewayRoutes.equals(other.gatewayRoutes)
        && identityAdminBrand.equals(other.identityAdminBrand)
        && business.equals(other.business);
  }
}
