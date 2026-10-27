package com.ken.platform.foundation.security;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;

/** 已验证内省结果；不含原 token，不把 HTTP 身份头当作主体。 */
public record CurrentPrincipal(
    String subject,
    String principalType,
    UUID tenantId,
    String clientId,
    Set<String> scopes,
    Set<String> permissions,
    Map<String, DataScope> dataScopes,
    Set<UUID> allowedTenantIds,
    Map<String, Object> attributes)
    implements OAuth2AuthenticatedPrincipal {
  public CurrentPrincipal {
    scopes = Set.copyOf(scopes);
    permissions = Set.copyOf(permissions);
    dataScopes = Map.copyOf(dataScopes);
    allowedTenantIds = Set.copyOf(allowedTenantIds);
    attributes = Map.copyOf(attributes);
  }

  @Override
  public String getName() {
    return subject;
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    List<GrantedAuthority> result = new ArrayList<>();
    scopes.stream()
        .sorted()
        .forEach(scope -> result.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
    permissions.stream()
        .sorted()
        .forEach(permission -> result.add(new SimpleGrantedAuthority("PERMISSION_" + permission)));
    return List.copyOf(result);
  }

  public void require(String scope, String permission, String type) {
    if (!principalType.equals(type) || !scopes.contains(scope) || !permissions.contains(permission))
      throw forbidden();
  }

  public DataScope dataScope(String permission) {
    var value = dataScopes.get(permission);
    if (!permissions.contains(permission) || value == null) throw forbidden();
    return value;
  }

  public UUID requireTenant() {
    if (tenantId == null || principalType.equals("platform")) throw forbidden();
    return tenantId;
  }

  @Override
  public String toString() {
    return "CurrentPrincipal[type=" + principalType + ", subject=" + subject + "]";
  }

  public static CurrentPrincipal parse(
      Map<String, Object> raw, String issuer, String audience, Clock clock) {
    try {
      if (!(raw.get("active") instanceof Boolean active)) throw unavailable();
      if (!active) throw invalid();
      String actualIssuer = text(raw.get("iss"));
      var audiences =
          raw.get("aud") instanceof String value ? Set.of(value) : strings(raw.get("aud"), false);
      if (!actualIssuer.equals(issuer) || !audiences.contains(audience)) throw invalid();
      Instant expires = date(raw.get("exp"));
      if (!expires.isAfter(clock.instant())) throw invalid();
      String type = text(raw.get("principal_type"));
      if (!Set.of("user", "platform", "service").contains(type)) throw unavailable();
      String client = identifier(raw.get("client_id"));
      String subject = text(raw.get("sub"));
      if (type.equals("service")) {
        if (!subject.equals(client)) throw unavailable();
      } else uuid(subject);
      UUID tenant = raw.get("tenant_id") == null ? null : uuid(raw.get("tenant_id"));
      Set<UUID> allowed = new HashSet<>();
      if (type.equals("service")) {
        for (String id : strings(raw.get("allowed_tenant_ids"), false)) allowed.add(uuid(id));
        if (allowed.size() == 1 ? !allowed.contains(tenant) : tenant != null) throw unavailable();
      } else if (type.equals("user") ? tenant == null : tenant != null) throw unavailable();
      if (!type.equals("service")
          && raw.containsKey("allowed_tenant_ids")
          && !strings(raw.get("allowed_tenant_ids"), false).isEmpty()) throw unavailable();
      Set<String> scopes = new HashSet<>();
      for (String value : textAllowEmpty(raw.get("scope")).split(" ")) {
        if (!value.isEmpty()) {
          identifier(value);
          scopes.add(value);
        }
      }
      Set<String> permissions = strings(raw.get("permissions"), true);
      if (!(raw.get("data_scopes") instanceof Map<?, ?> ranges)) throw unavailable();
      Map<String, DataScope> data = new HashMap<>();
      ranges.forEach(
          (key, value) -> {
            if (!(key instanceof String name)
                || !permissions.contains(name)
                || !(value instanceof String label)) throw unavailable();
            data.put(name, DataScope.valueOf(label));
          });
      if (type.equals("platform") && !data.isEmpty()) throw unavailable();
      Map<String, Object> attributes = new HashMap<>();
      attributes.put("active", true);
      attributes.put("iss", actualIssuer);
      attributes.put("aud", audiences.stream().sorted().toList());
      attributes.put("exp", expires);
      if (raw.containsKey("iat")) attributes.put("iat", date(raw.get("iat")));
      attributes.put("sub", subject);
      attributes.put("client_id", client);
      attributes.put("principal_type", type);
      attributes.put("scope", scopes.stream().sorted().toList());
      attributes.put("permissions", permissions.stream().sorted().toList());
      attributes.put(
          "data_scopes",
          data.entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toUnmodifiableMap(
                      Map.Entry::getKey, entry -> entry.getValue().name())));
      if (tenant != null) attributes.put("tenant_id", tenant.toString());
      if (type.equals("service"))
        attributes.put(
            "allowed_tenant_ids", allowed.stream().map(UUID::toString).sorted().toList());
      return new CurrentPrincipal(
          subject, type, tenant, client, scopes, permissions, data, allowed, attributes);
    } catch (OAuth2IntrospectionException known) {
      throw known;
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static Set<String> strings(Object raw, boolean permissions) {
    if (!(raw instanceof List<?> values)) throw unavailable();
    Set<String> result = new HashSet<>();
    for (Object item : values) {
      String value = text(item);
      if (!value.matches(permissions ? "[a-z][a-z0-9:_-]{0,159}" : "[A-Za-z0-9._:-]{1,160}"))
        throw unavailable();
      result.add(value);
    }
    return Set.copyOf(result);
  }

  private static String text(Object raw) {
    String value = textAllowEmpty(raw);
    if (value.isEmpty()) throw unavailable();
    return value;
  }

  private static String textAllowEmpty(Object raw) {
    if (!(raw instanceof String value)) throw unavailable();
    return value;
  }

  private static String identifier(Object raw) {
    String value = text(raw);
    if (!value.matches("[A-Za-z0-9._:-]{1,160}")) throw unavailable();
    return value;
  }

  private static UUID uuid(Object raw) {
    String value = text(raw);
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw unavailable();
    return id;
  }

  private static Instant date(Object raw) {
    if (!(raw instanceof Integer || raw instanceof Long)) throw unavailable();
    long value = ((Number) raw).longValue();
    if (value < 0 || value > 253402300799L) throw unavailable();
    return Instant.ofEpochSecond(value);
  }

  private static PlatformException forbidden() {
    return new PlatformException("FORBIDDEN", "当前主体、授权范围或操作权限不足", 403);
  }

  static BadOpaqueTokenException invalid() {
    return new BadOpaqueTokenException("访问令牌无效");
  }

  static OAuth2IntrospectionException unavailable() {
    return new OAuth2IntrospectionException("身份内省暂不可用");
  }
}
