package com.ken.platform.gateway.security;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.security.ResourceIdentitySettings;
import com.ken.platform.foundation.security.ResourceIntrospector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Gateway 为每个目标 audience 单独持有自己的内省身份，不共享资源服务的秘密。 */
public final class GatewayIntrospectionClients implements AutoCloseable {
  private final Map<String, ResourceIntrospector> clients;

  public GatewayIntrospectionClients(
      Environment environment,
      RuntimeParameters parameters,
      ServiceDiscovery discovery,
      Clock clock) {
    var audiences =
        parameters.gatewayRoutes().stream()
            .filter(route -> route.api())
            .map(route -> route.audience())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    String reference = environment.getProperty("GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE");
    var created = new HashMap<String, ResourceIntrospector>();
    try {
      if (audiences.isEmpty()) {
        if (reference != null && !reference.isBlank()) throw invalid();
      } else {
        if (reference == null || reference.isBlank()) throw invalid();
        Path file = Path.of(reference);
        if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 65536)
          throw invalid();
        var json =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        Object parsed = json.readValue(Files.readAllBytes(file), Object.class);
        if (!(parsed instanceof Map<?, ?> document)
            || !document.keySet().equals(Set.of("readers"))
            || !(document.get("readers") instanceof java.util.List<?> readers)
            || readers.size() > 64) throw invalid();
        var identifiers = new HashSet<String>();
        for (Object value : readers) {
          if (!(value instanceof Map<?, ?> reader)
              || !reader.keySet().equals(Set.of("audience", "client_id", "secret_file"))
              || !(reader.get("audience") instanceof String audience)
              || !audiences.contains(audience)
              || created.containsKey(audience)
              || !(reader.get("client_id") instanceof String id)
              || !identifiers.add(id)
              || !(reader.get("secret_file") instanceof String secretFile)) throw invalid();
          var settings =
              new ResourceIdentitySettings(
                  environment.getRequiredProperty("APP_ENV"),
                  environment.getRequiredProperty("PUBLIC_ORIGIN"),
                  audience,
                  id,
                  Path.of(secretFile));
          created.put(audience, new ResourceIntrospector(discovery, settings, parameters, clock));
        }
        if (!created.keySet().equals(audiences)) throw invalid();
      }
      clients = Map.copyOf(created);
    } catch (Exception failure) {
      created.values().forEach(ResourceIntrospector::close);
      throw invalid();
    }
  }

  public ResourceIntrospector forAudience(String audience) {
    var client = clients.get(audience);
    if (client == null)
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "目标资源内省未装配");
    return client;
  }

  public boolean empty() {
    return clients.isEmpty();
  }

  public void verify() {
    clients.values().forEach(ResourceIntrospector::verifyIdentity);
  }

  @Override
  public void close() {
    clients.values().forEach(ResourceIntrospector::close);
  }

  @Override
  public String toString() {
    return "GatewayIntrospectionClients[audiences=" + clients.keySet() + "]";
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Gateway 独立内省凭据清单缺失或不合法");
  }
}
