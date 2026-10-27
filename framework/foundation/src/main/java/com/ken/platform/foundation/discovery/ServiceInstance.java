package com.ken.platform.foundation.discovery;

import java.net.URI;

/** 只接收注册目录中的 HTTP(S) 端点，不把调用参数当作任意 URL。 */
public record ServiceInstance(String host, int port, String scheme) {
  public ServiceInstance {
    if (host == null
        || host.isBlank()
        || port < 1
        || port > 65535
        || !("http".equals(scheme) || "https".equals(scheme))) {
      throw new IllegalArgumentException("服务注册地址不合法");
    }
    String address = host.contains(":") ? "[" + host + "]" : host;
    URI endpoint;
    try {
      endpoint = URI.create(scheme + "://" + address + ":" + port);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("服务主机字段不合法");
    }
    if (endpoint.getHost() == null
        || endpoint.getRawUserInfo() != null
        || !endpoint.getRawPath().isEmpty()
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null) {
      throw new IllegalArgumentException("服务主机字段不能携带路径或认证信息");
    }
  }

  public URI origin() {
    String address = host.contains(":") ? "[" + host + "]" : host;
    return URI.create(scheme + "://" + address + ":" + port);
  }
}
