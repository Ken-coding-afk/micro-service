package com.ken.platform.foundation.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/** 在 SCA 读取任意远程属性之前固定环境来源，禁止自动注册和未校验的 Nacos Spring import。 */
public final class PlatformEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  static final Set<String> FIELDS =
      Set.of(
          "PROJECT_CODE",
          "APP_ENV",
          "SERVICE_NAME",
          "SERVICE_BIND_HOST",
          "SERVICE_BIND_PORT",
          "SERVICE_ADVERTISE_HOST",
          "SERVICE_ADVERTISE_PORT",
          "SERVICE_SCHEME",
          "ROLE",
          "NACOS_SERVER_ADDR",
          "NACOS_NAMESPACE",
          "NACOS_RUNTIME_USER",
          "NACOS_RUNTIME_PASSWORD_SECRET_FILE");

  static boolean enabled() {
    return ClassUtils.isPresent("com.alibaba.cloud.nacos.NacosConfigManager", null)
        && ClassUtils.isPresent("com.alibaba.cloud.nacos.NacosServiceManager", null);
  }

  static Map<String, String> bindings(ConfigurableEnvironment environment) {
    Map<String, String> values = new HashMap<>();
    for (String field : FIELDS) {
      String value = environment.getProperty(field);
      if (value != null) {
        values.put(field, value);
      }
    }
    environment.getSystemEnvironment().keySet().stream()
        .filter(key -> key.startsWith("NACOS_") || key.startsWith("SERVICE_"))
        .filter(key -> !FIELDS.contains(key))
        .findFirst()
        .ifPresent(
            key -> {
              throw new IllegalArgumentException("应用收到未声明的环境字段: " + key);
            });
    return values;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!enabled()) {
      return;
    }
    String imported = environment.getProperty("spring.config.import", "");
    if (imported.contains("nacos:")) {
      throw new IllegalArgumentException("Nacos 运行配置必须经过框架类型校验，不能直接导入 Spring 属性");
    }
    var values = bindings(environment);
    var settings = NacosSettings.from(values);
    var declared =
        application.getAllSources().stream()
            .filter(source -> source instanceof Class<?>)
            .map(source -> (Class<?>) source)
            .filter(type -> type.isAnnotationPresent(PlatformService.class))
            .map(type -> type.getAnnotation(PlatformService.class).value())
            .distinct()
            .toList();
    if (declared.size() != 1 || !declared.getFirst().equals(settings.serviceName())) {
      throw new IllegalArgumentException("SERVICE_NAME 必须匹配启动制品的 @PlatformService 声明");
    }
    int port;
    try {
      port = Integer.parseInt(values.getOrDefault("SERVICE_BIND_PORT", "0"));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("监听端口不合法");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("监听端口不合法");
    }
    var fixed = new HashMap<String, Object>();
    fixed.put("spring.application.name", settings.serviceName());
    // Boot 原生结构化日志包含 MDC；自定义日志配置仍由业务显式维护这些字段。
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "platform-log-defaults",
                Map.of(
                    "logging.structured.format.console",
                    "logstash",
                    "logging.structured.json.add.service",
                    settings.serviceName())));
    if (ClassUtils.isPresent("org.springframework.jdbc.core.JdbcTemplate", null))
      fixed.putAll(PlatformDatabaseSettings.from(environment).springProperties());
    boolean docs =
        "local".equals(values.get("APP_ENV"))
            && !Set.of("identity-service", "api-gateway").contains(settings.serviceName());
    fixed.put("springdoc.api-docs.enabled", docs);
    fixed.put("springdoc.swagger-ui.enabled", docs);
    fixed.put("springdoc.swagger-ui.oauth.client-id", settings.serviceName() + "-swagger");
    fixed.put("springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant", true);
    fixed.put("springdoc.swagger-ui.persist-authorization", false);
    fixed.put(
        "springdoc.swagger-ui.oauth2-redirect-url",
        "http://localhost:" + port + "/swagger-ui/oauth2-redirect.html");
    fixed.put("management.endpoint.health.probes.enabled", true);
    fixed.put("management.endpoint.health.probes.add-additional-paths", true);
    fixed.put("management.endpoint.health.show-details", "never");
    fixed.put("server.address", values.getOrDefault("SERVICE_BIND_HOST", "0.0.0.0"));
    // 先保留真实连接对端，代理信任由框架 CIDR 边界处理，不能提前被转发头改写。
    fixed.put("server.forward-headers-strategy", "none");
    fixed.put("server.port", port);
    fixed.put("spring.cloud.nacos.config.import-check.enabled", false);
    fixed.put("spring.cloud.nacos.config.refresh-enabled", false);
    fixed.put("spring.cloud.nacos.discovery.server-addr", settings.serverAddress());
    fixed.put("spring.cloud.nacos.discovery.namespace", settings.namespace());
    fixed.put("spring.cloud.nacos.discovery.register-enabled", false);
    fixed.put("spring.cloud.nacos.discovery.naming-load-cache-at-start", false);
    fixed.put("spring.cloud.service-registry.auto-registration.enabled", false);
    fixed.put("spring.cloud.loadbalancer.nacos.enabled", false);
    fixed.put("spring.cloud.loadbalancer.cache.enabled", false);
    fixed.put("spring.cloud.gateway.server.webflux.discovery.locator.enabled", false);
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource("platform-environment", fixed) {
              @Override
              public String toString() {
                return "platform-environment[protected]";
              }
            });
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }
}
