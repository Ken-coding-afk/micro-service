package com.ken.platform.foundation.config;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;

/** JDBC 服务的环境绑定；只读取指定秘密文件，不接受 URL/明文密码的第二来源。 */
public record PlatformDatabaseSettings(
    String host, int port, String database, String user, Path passwordFile, String appEnv) {
  public static PlatformDatabaseSettings from(Environment environment) {
    if (environment instanceof org.springframework.core.env.ConfigurableEnvironment configured) {
      for (var source : configured.getPropertySources()) {
        if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
          for (String name : enumerable.getPropertyNames()) {
            if (name.startsWith("spring.datasource.") || name.startsWith("SPRING_DATASOURCE_"))
              throw new IllegalArgumentException("数据库只能使用声明的环境绑定与秘密文件");
          }
        }
      }
    }
    if (environment.getProperty("DB_PASSWORD") != null
        || environment.getProperty("JDBC_URL") != null
        || environment.getProperty("spring.datasource.url") != null
        || environment.getProperty("spring.datasource.username") != null
        || environment.getProperty("spring.datasource.password") != null
        || environment.getProperty("spring.datasource.hikari.connection-init-sql") != null)
      throw new IllegalArgumentException("数据库只能使用声明的环境绑定与秘密文件");
    try {
      String host = environment.getRequiredProperty("PG_HOST");
      int port = Integer.parseInt(environment.getRequiredProperty("PG_PORT"));
      String database = environment.getRequiredProperty("DB_NAME"),
          user = environment.getRequiredProperty("DB_USER");
      String app = environment.getRequiredProperty("APP_ENV");
      Path file = Path.of(environment.getRequiredProperty("DB_PASSWORD_SECRET_FILE"));
      if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
      if (host.contains(":")) InetAddress.getByName(host); // 仅数值 IPv6，不做主机名解析。
      else if (!host.matches("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
          || host.contains("..")) throw new IllegalArgumentException();
      if (port < 1
          || port > 65535
          || !database.matches("[a-z][a-z0-9_]{0,62}")
          || !user.matches("[a-z][a-z0-9_]{0,62}")
          || !Set.of("local", "test", "prod").contains(app)
          || !file.isAbsolute()
          || !Files.isRegularFile(file)
          || Files.readString(file).strip().isEmpty()) throw new IllegalArgumentException();
      return new PlatformDatabaseSettings(host, port, database, user, file, app);
    } catch (Exception invalid) {
      throw new IllegalArgumentException("数据库环境绑定或秘密文件缺失/不合法");
    }
  }

  public Map<String, Object> springProperties() {
    try {
      String address = host.contains(":") ? "[" + host + "]" : host;
      String tls =
          appEnv.equals("local")
              ? "sslmode=disable"
              : "sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory";
      Map<String, Object> values = new LinkedHashMap<>();
      values.put(
          "spring.datasource.url",
          "jdbc:postgresql://"
              + address
              + ":"
              + port
              + "/"
              + database
              + "?"
              + tls
              + "&connectTimeout=2&socketTimeout=10");
      values.put("spring.datasource.username", user);
      String password = Files.readString(passwordFile).strip();
      if (password.isEmpty()) throw new IllegalArgumentException();
      values.put("spring.datasource.password", password);
      values.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
      values.put("spring.datasource.hikari.connection-timeout", 10000);
      values.put("spring.flyway.enabled", false);
      values.put("spring.sql.init.mode", "never");
      values.put("spring.jpa.hibernate.ddl-auto", "none");
      values.put("management.endpoint.health.group.readiness.include", "readinessState,db");
      return values;
    } catch (Exception unavailable) {
      throw new IllegalArgumentException("数据库秘密文件当前不可用");
    }
  }

  @Override
  public String toString() {
    return "PlatformDatabaseSettings[protected]";
  }
}
