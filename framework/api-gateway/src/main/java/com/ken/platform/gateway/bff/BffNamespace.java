package com.ken.platform.gateway.bff;

/** 入口、客户端和服务端会话的固定对应；请求头、参数或旧会话不能改变命名空间。 */
public enum BffNamespace {
  PLATFORM("/bff/identity/platform", "identity-admin-platform"),
  TENANT("/bff/identity/tenant", "identity-admin-tenant"),
  WEB("/bff/web", "web-bff");

  private final String path;
  private final String clientId;

  BffNamespace(String path, String clientId) {
    this.path = path;
    this.clientId = clientId;
  }

  public String path() {
    return path;
  }

  public String clientId() {
    return clientId;
  }

  public static BffNamespace forPath(String path) {
    for (var namespace : values()) {
      if (path.equals(namespace.path) || path.startsWith(namespace.path + "/")) return namespace;
    }
    return null;
  }
}
