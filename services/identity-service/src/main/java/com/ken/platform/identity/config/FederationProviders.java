package com.ken.platform.identity.config;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** 使用 Spring 固定 registration 配置；没有数据库配置平台或任意 URL 测试代理。 */
public final class FederationProviders {
  private final Map<String, ClientRegistration> clients = new LinkedHashMap<>();
  private final Map<String, Set<String>> tenants = new HashMap<>();

  public FederationProviders(ClientRegistrationRepository repository, Environment env) {
    if (!(repository instanceof Iterable<?> registrations))
      throw new IllegalArgumentException("提供方必须为固定注册列表");
    var hosts = csv(env.getRequiredProperty("platform.federation.allowed-hosts"));
    String origin = env.getRequiredProperty("PUBLIC_ORIGIN");
    boolean local = "local".equals(env.getProperty("APP_ENV"));
    for (Object item : registrations) {
      var client = (ClientRegistration) item;
      if (!org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE
              .equals(client.getAuthorizationGrantType())
          || client.getClientSecret() == null
          || client.getClientSecret().isBlank())
        throw new IllegalArgumentException("第三方登录要求机密授权码客户端");
      String id = client.getRegistrationId();
      if (!id.matches("[a-z][a-z0-9-]{0,63}")
          || !client.getRedirectUri().equals(origin + "/login/oauth2/code/" + id))
        throw new IllegalArgumentException("提供方 ID 或固定回调不合法");
      var details = client.getProviderDetails();
      for (String endpoint :
          List.of(
              details.getAuthorizationUri(),
              details.getTokenUri(),
              details.getUserInfoEndpoint().getUri())) check(endpoint, hosts, local);
      if (client.getScopes().contains("openid")) {
        check(details.getIssuerUri(), hosts, local);
        check(details.getJwkSetUri(), hosts, local);
      } else if (!details.getAuthorizationUri().equals("https://github.com/login/oauth/authorize")
          || !details.getTokenUri().equals("https://github.com/login/oauth/access_token")
          || !details.getUserInfoEndpoint().getUri().equals("https://api.github.com/user")
          || !"id".equals(details.getUserInfoEndpoint().getUserNameAttributeName()))
        throw new IllegalArgumentException("非 OIDC 仅支持固定 GitHub OAuth2 接入");
      var selected = csv(env.getRequiredProperty("platform.federation.tenants." + id));
      if (selected.isEmpty() || selected.stream().anyMatch(t -> !t.matches("[a-z][a-z0-9-]{0,63}")))
        throw new IllegalArgumentException("必须明确提供方适用租户");
      tenants.put(id, selected);
      clients.put(id, client);
    }
    if (clients.isEmpty()) throw new IllegalArgumentException("启用第三方登录但未登记提供方");
  }

  public List<String> ids() {
    return List.copyOf(clients.keySet());
  }

  public ClientRegistration require(String id, String tenant) {
    if (!tenants.getOrDefault(id, Set.of()).contains(tenant))
      throw new IllegalArgumentException("提供方不适用于该租户");
    return clients.get(id);
  }

  public String authority(String id) {
    var client = clients.get(id);
    return client.getScopes().contains("openid")
        ? client.getProviderDetails().getIssuerUri()
        : "https://api.github.com/user";
  }

  private static Set<String> csv(String input) {
    return Set.copyOf(
        Arrays.stream(input.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
  }

  private static void check(String value, Set<String> hosts, boolean local) {
    var uri = URI.create(value == null ? "" : value);
    if (uri.getHost() == null
        || Set.of("169.254.169.254", "metadata.google.internal", "100.100.100.200")
            .contains(uri.getHost())
        || !hosts.contains(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme())
            || local
                && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
      throw new IllegalArgumentException("提供方端点不在明确允许的 HTTPS 地址范围");
  }
}
