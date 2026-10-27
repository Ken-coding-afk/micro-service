package com.ken.platform.foundation.config;

import com.ken.platform.foundation.discovery.ServiceInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** 应用仅取得运行身份；秘密从受控文件读取，不接受管理员密码回退。 */
public record NacosSettings(
    String serviceName,
    String namespace,
    String serverAddress,
    String username,
    Path passwordFile,
    String advertiseHost,
    int advertisePort,
    String role,
    String scheme) {
  public NacosSettings {
    new ServiceInstance(advertiseHost, advertisePort, scheme);
    if (serviceName == null
        || !serviceName.matches("[a-z][a-z0-9-]*")
        || role == null
        || !SetHolder.ROLES.contains(role)
        || scheme == null
        || !SetHolder.SCHEMES.contains(scheme)
        || advertiseHost == null
        || SetHolder.INVALID_HOSTS.contains(advertiseHost.toLowerCase(java.util.Locale.ROOT))
        || advertiseHost.startsWith("127.")
        || advertisePort < 1
        || advertisePort > 65535
        || namespace == null
        || namespace.isBlank()
        || serverAddress == null
        || serverAddress.isBlank()
        || username == null
        || username.isBlank()
        || passwordFile == null
        || !Files.isRegularFile(passwordFile)) {
      throw new IllegalArgumentException("Nacos 环境绑定不完整或注册地址不合法");
    }
  }

  public static NacosSettings from(Map<String, String> values) {
    String project = values.get("PROJECT_CODE");
    String environment = values.get("APP_ENV");
    if (project == null
        || !project.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        || environment == null
        || !java.util.Set.of("local", "test", "prod").contains(environment)
        || !(project + "-" + environment).equals(values.get("NACOS_NAMESPACE"))) {
      throw new IllegalArgumentException("Nacos namespace 必须匹配项目与环境");
    }
    String scheme = values.getOrDefault("SERVICE_SCHEME", "http");
    if (!environment.equals("local") && !scheme.equals("https")) {
      throw new IllegalArgumentException("测试/生产服务需要 HTTPS 端点");
    }
    return new NacosSettings(
        values.get("SERVICE_NAME"),
        values.get("NACOS_NAMESPACE"),
        values.get("NACOS_SERVER_ADDR"),
        values.get("NACOS_RUNTIME_USER"),
        Path.of(values.getOrDefault("NACOS_RUNTIME_PASSWORD_SECRET_FILE", "")),
        values.get("SERVICE_ADVERTISE_HOST"),
        Integer.parseInt(values.getOrDefault("SERVICE_ADVERTISE_PORT", "0")),
        values.getOrDefault("ROLE", "api"),
        scheme);
  }

  public Properties connectionProperties() {
    try {
      String password = Files.readString(passwordFile).strip();
      if (password.isEmpty()) {
        throw new IllegalArgumentException("Nacos 秘密文件为空");
      }
      Properties properties = new Properties();
      properties.setProperty("serverAddr", serverAddress);
      properties.setProperty("namespace", namespace);
      properties.setProperty("username", username);
      properties.setProperty("password", password);
      return properties;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Nacos 秘密文件不可读");
    }
  }

  private static final class SetHolder {
    static final java.util.Set<String> ROLES = java.util.Set.of("api", "worker");
    static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    static final java.util.Set<String> INVALID_HOSTS =
        java.util.Set.of("0.0.0.0", "::", "localhost", "127.0.0.1", "::1");
  }
}
