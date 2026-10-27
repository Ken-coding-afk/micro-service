package com.ken.platform.gateway.bff;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** BFF 只声明申请 scopes；实际允许范围与租户始终由身份库及实时 token 检查决定。 */
public final class BffClientSettings {
  private final String origin;
  private final boolean secure;
  private final Map<BffNamespace, ClientRegistration> clients;

  public BffClientSettings(Environment environment) {
    try {
      String app = environment.getRequiredProperty("APP_ENV");
      var uri = URI.create(environment.getRequiredProperty("PUBLIC_ORIGIN"));
      boolean local =
          app.equals("local")
              && "http".equals(uri.getScheme())
              && Set.of("localhost", "127.0.0.1", "[::1]")
                  .contains(uri.getHost() == null ? "" : uri.getHost());
      if (!Set.of("local", "test", "prod").contains(app)
          || uri.getHost() == null
          || !(local || "https".equals(uri.getScheme()))
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw invalid();
      origin = environment.getRequiredProperty("PUBLIC_ORIGIN").replaceAll("/$", "");
      secure = !local;
      var json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
      Object parsed =
          json.readValue(
              read(environment.getRequiredProperty("GATEWAY_BFF_CLIENTS_SECRET_FILE"), 65536),
              Object.class);
      if (!(parsed instanceof Map<?, ?> root)
          || !Set.of("platform_secret_file", "tenant_secret_file", "web").containsAll(root.keySet())
          || !(root.get("platform_secret_file") instanceof String platform)
          || !(root.get("tenant_secret_file") instanceof String tenant)) throw invalid();
      var registrations = new EnumMap<BffNamespace, ClientRegistration>(BffNamespace.class);
      registrations.put(
          BffNamespace.PLATFORM,
          registration(BffNamespace.PLATFORM, read(platform, 128), platformScopes(environment)));
      registrations.put(
          BffNamespace.TENANT,
          registration(
              BffNamespace.TENANT,
              read(tenant, 128),
              Set.of(
                  "profile",
                  "identity.users",
                  "identity.roles",
                  "identity.permissions",
                  "identity.sessions",
                  "identity.applications",
                  "identity.providers",
                  "identity.security",
                  "identity.audit")));
      if (root.containsKey("web")) {
        if (!(root.get("web") instanceof Map<?, ?> web)
            || !web.keySet().equals(Set.of("secret_file", "requested_scopes"))
            || !(web.get("secret_file") instanceof String file)
            || !(web.get("requested_scopes") instanceof java.util.List<?> requested)
            || requested.isEmpty()
            || requested.size() > 100) throw invalid();
        Set<String> scopes = new HashSet<>();
        for (Object value : requested) {
          if (!(value instanceof String scope)
              || !scope.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
              || scope.equals("openid")
              || !scopes.add(scope)) throw invalid();
        }
        if (!scopes.contains("profile") || String.join(" ", scopes).length() > 1000)
          throw invalid();
        registrations.put(
            BffNamespace.WEB, registration(BffNamespace.WEB, read(file, 128), Set.copyOf(scopes)));
      }
      clients = Map.copyOf(registrations);
    } catch (Exception failure) {
      throw invalid();
    }
  }

  static Set<String> platformScopes(Environment environment) {
    var scopes =
        new HashSet<>(
            Set.of(
                "profile",
                "platform.admins",
                "platform.tenants",
                "platform.clients",
                "platform.security",
                "platform.audit"));
    if (java.util.Arrays.asList(environment.getProperty("COMPOSE_PROFILES", "").split(","))
        .contains("monitoring")) scopes.add("platform.monitoring");
    return Set.copyOf(scopes);
  }

  private ClientRegistration registration(
      BffNamespace namespace, String secret, Set<String> scopes) {
    if (!secret.matches("[A-Za-z0-9_-]{32,64}")) throw invalid();
    return ClientRegistration.withRegistrationId(
            namespace.name().toLowerCase(java.util.Locale.ROOT))
        .clientId(namespace.clientId())
        .clientSecret(secret)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(origin + namespace.path() + "/callback")
        .scope(scopes)
        .authorizationUri(origin + "/oauth2/authorize")
        .tokenUri("http://identity-service/oauth2/token")
        .userInfoUri("http://identity-service/api/v1/identity/me")
        .userNameAttributeName("sub")
        .clientName(namespace.clientId())
        .build();
  }

  public String origin() {
    return origin;
  }

  public boolean secure() {
    return secure;
  }

  public Set<BffNamespace> namespaces() {
    return clients.keySet();
  }

  public ClientRegistration client(BffNamespace namespace) {
    var client = clients.get(namespace);
    if (client == null) throw invalid();
    return client;
  }

  public ReactiveClientRegistrationRepository repository() {
    return new InMemoryReactiveClientRegistrationRepository(new ArrayList<>(clients.values()));
  }

  private static String read(String value, int limit) throws Exception {
    Path file = Path.of(value);
    if (!file.isAbsolute() || !Files.isRegularFile(file)) throw invalid();
    byte[] bytes;
    try (var input = Files.newInputStream(file)) {
      bytes = input.readNBytes(limit + 1);
    }
    if (bytes.length == 0 || bytes.length > limit) throw invalid();
    return StandardCharsets.UTF_8
        .newDecoder()
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()
        .strip();
  }

  @Override
  public String toString() {
    return "BffClientSettings[namespaces=" + clients.keySet() + "]";
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("BFF 来源或独立客户端秘密描述不合法");
  }
}
