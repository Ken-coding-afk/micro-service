package com.ken.platform.gateway.security;

import java.util.Set;

public final class IdentityEndpoints {
  private IdentityEndpoints() {}

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
          "/identity/password/reset",
          "/identity/session",
          "/identity/consent",
          "/identity/logout",
          "/identity/external",
          "/identity/external/**",
          "/oauth2/authorization/*",
          "/login/oauth2/code/*",
          "/identity/admin/**",
          "/api/v1/identity/admin-config");

  public static Set<String> paths() {
    return IDENTITY_PATHS;
  }
}
