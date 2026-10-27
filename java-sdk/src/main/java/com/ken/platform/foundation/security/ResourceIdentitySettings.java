package com.ken.platform.foundation.security;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;

/** 资源服务独立内省身份；配置仅来自环境绑定和秘密文件。 */
public record ResourceIdentitySettings(
    String appEnv, String issuer, String audience, String clientId, Path secretFile) {
  public ResourceIdentitySettings {
    try {
      URI origin = URI.create(issuer);
      boolean https = origin.getScheme().equals("https");
      boolean local =
          appEnv.equals("local")
              && origin.getScheme().equals("http")
              && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(origin.getHost());
      if (!Set.of("local", "test", "prod").contains(appEnv)
          || !(https || local)
          || origin.getHost() == null
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || origin.getPort() == 0
          || origin.getPort() > 65535
          || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
          || !audience.matches("[A-Za-z0-9._:-]{1,160}")
          || !clientId.matches("[A-Za-z0-9._:-]{1,128}")
          || !secretFile.isAbsolute()
          || !Files.isRegularFile(secretFile)
          || Files.readString(secretFile).strip().isEmpty()) throw new IllegalArgumentException();
    } catch (Exception invalid) {
      throw new IllegalArgumentException("资源内省环境绑定或秘密引用不合法");
    }
  }

  public static ResourceIdentitySettings from(Environment environment) {
    try {
      if (environment.getProperty("INTROSPECTION_CLIENT_SECRET") != null)
        throw new IllegalArgumentException();
      return new ResourceIdentitySettings(
          environment.getRequiredProperty("APP_ENV"),
          environment.getRequiredProperty("PUBLIC_ORIGIN"),
          environment.getRequiredProperty("RESOURCE_AUDIENCE"),
          environment.getRequiredProperty("INTROSPECTION_CLIENT_ID"),
          Path.of(environment.getRequiredProperty("INTROSPECTION_CLIENT_SECRET_FILE")));
    } catch (Exception invalid) {
      throw new IllegalArgumentException("资源内省环境绑定或秘密引用不合法");
    }
  }

  @Override
  public String toString() {
    return "ResourceIdentitySettings[protected]";
  }
}
