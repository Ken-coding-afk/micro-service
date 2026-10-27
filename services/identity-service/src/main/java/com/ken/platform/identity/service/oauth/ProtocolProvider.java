package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 官方 Provider 外围的事务、当前策略和签发上限检查；协议校验仍由官方完成。 */
public final class ProtocolProvider implements AuthenticationProvider {
  static final class AuditedFailure extends OAuth2AuthenticationException
      implements AuditedProtocolFailure {
    AuditedFailure(String code) {
      super(new OAuth2Error(code));
    }
  }

  public enum Operation {
    CLIENT_AUTHENTICATION,
    TOKEN,
    INTROSPECTION,
    REVOCATION
  }

  private final AuthenticationProvider delegate;
  private final Operation operation;
  private final ClientStore clients;
  private final ProtocolAuthorizations authorizations;
  private final SecurityAuditRepository audits;
  private final TransactionTemplate transactions;
  private final TransactionTemplate events;

  public ProtocolProvider(
      AuthenticationProvider delegate,
      Operation operation,
      ClientStore clients,
      ProtocolAuthorizations authorizations,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager) {
    this.delegate = delegate;
    this.operation = operation;
    this.clients = clients;
    this.authorizations = authorizations;
    this.audits = audits;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("协议入口必须拥有独立事务边界");
    }
    String request =
        CallContexts.current() == null
            ? UUID.randomUUID().toString()
            : CallContexts.current().requestId();
    String action = "identity.oauth." + operation.name().toLowerCase(java.util.Locale.ROOT);
    String[] actor = {null};
    try {
      return transactions.execute(
          status -> {
            if (operation == Operation.CLIENT_AUTHENTICATION)
              return delegate.authenticate(authentication);
            var client = authenticated(authentication);
            var current = clients.lockSnapshot(client.getRegisteredClient().getId());
            if (current == null
                || current.policy().status() != Status.ACTIVE
                || !Objects.equals(
                    current.client().getClientSecret(),
                    client.getRegisteredClient().getClientSecret())
                || !current
                    .client()
                    .getClientAuthenticationMethods()
                    .contains(client.getClientAuthenticationMethod())) {
              throw error("invalid_client");
            }
            actor[0] = current.client().getClientId();
            Authentication result;
            if (operation == Operation.TOKEN) {
              // 用户分支还需租户登录/会话绑定，未接入前不能以客户端身份代替用户。
              if (!(authentication instanceof OAuth2ClientCredentialsAuthenticationToken grant)
                  || current.policy().principalType() != PrincipalType.SERVICE)
                throw error("unauthorized_client");
              if (!current.client().getScopes().containsAll(grant.getScopes()))
                throw error("invalid_scope");
              if (grant.getAdditionalParameters().keySet().stream()
                  .anyMatch(Set.of("tenant_id", "tenant_code", "audience", "resource")::contains))
                throw error("invalid_request");
              if (!clients
                  .activeTenants(current.policy().allowedTenantIds())
                  .equals(current.policy().allowedTenantIds())) {
                throw error("unauthorized_client");
              }
              if (clients.audienceCount(current.policy().audiences())
                  != current.policy().audiences().size()) {
                throw error("unauthorized_client");
              }
              result = authorizations.issuing(current, () -> delegate.authenticate(authentication));
            } else if (operation == Operation.INTROSPECTION) {
              result = introspect(authentication, current);
            } else {
              result = delegate.authenticate(authentication);
            }
            if (operation != Operation.INTROSPECTION)
              audit(action, "SUCCESS", actor[0], null, request);
            return result;
          });
    } catch (RuntimeException failure) {
      String code =
          failure instanceof OAuth2AuthenticationException known
              ? known.getError().getErrorCode()
              : failure instanceof DataAccessException
                      || failure instanceof com.ken.platform.foundation.http.PlatformException
                  ? "temporarily_unavailable"
                  : "server_error";
      if (!code.matches("[a-z_]{1,64}")) code = "server_error";
      String safeCode = code;
      try {
        events.execute(
            status -> {
              audit(action, "FAILURE", actor[0], safeCode, request);
              return null;
            });
      } catch (RuntimeException unavailable) {
        throw error("temporarily_unavailable");
      }
      throw new AuditedFailure(code);
    }
  }

  private Authentication introspect(Authentication authentication, ClientStore.Snapshot caller) {
    var request = (OAuth2TokenIntrospectionAuthenticationToken) authentication;
    var registered = caller.client();
    var scopes = registered.getScopes();
    var targets = scopes.stream().filter(s -> s.startsWith("internal.introspect:")).toList();
    if (caller.policy().principalType() != PrincipalType.SERVICE
        || caller.policy().ownerTenantId() != null
        || !caller.policy().allowedTenantIds().isEmpty()
        || targets.size() != 1
        || !caller.policy().audiences().equals(Set.of("identity-service")))
      throw error("unauthorized_client");
    String audience = targets.getFirst().substring(20);
    if (!clients.audienceExists(audience)) throw error("unauthorized_client");
    var authorization =
        authorizations.findByToken(request.getToken(), OAuth2TokenType.ACCESS_TOKEN);
    var inactive = OAuth2TokenIntrospection.builder(false).build();
    if (authorization == null
        || authorization.getAccessToken() == null
        || !authorization.getAccessToken().isActive()
        || !"service".equals(authorization.getAttribute(ProtocolAuthorizations.TYPE)))
      return response(request, inactive);
    var current = clients.lockSnapshot(authorization.getRegisteredClientId());
    if (current == null
        || current.policy().status() != Status.ACTIVE
        || current.policy().principalType() != PrincipalType.SERVICE
        || !strings(authorization.getAttribute(ProtocolAuthorizations.AUDIENCES)).contains(audience)
        || !current.policy().audiences().contains(audience)) return response(request, inactive);
    var issuedTenants = strings(authorization.getAttribute(ProtocolAuthorizations.TENANTS));
    var tenants = new HashSet<>(current.policy().allowedTenantIds());
    tenants.removeIf(id -> !issuedTenants.contains(id.toString()));
    tenants.retainAll(clients.activeTenants(tenants));
    if (!issuedTenants.isEmpty() && tenants.isEmpty()) return response(request, inactive);
    var effective = new HashSet<>(authorization.getAccessToken().getToken().getScopes());
    effective.retainAll(current.client().getScopes());
    if (audience.equals("identity-service")) {
      effective.removeIf(
          s -> !(s.startsWith("catalog.publish:") || s.startsWith("internal.introspect:")));
    } else {
      effective.retainAll(clients.businessScopes(PrincipalType.SERVICE, Set.of(audience)));
    }
    if (effective.isEmpty()) return response(request, inactive);
    var token = authorization.getAccessToken().getToken();
    var claims =
        OAuth2TokenIntrospection.builder(true)
            .clientId(current.client().getClientId())
            .subject(current.client().getClientId())
            .tokenType(token.getTokenType().getValue())
            .issuedAt(token.getIssuedAt())
            .expiresAt(token.getExpiresAt())
            .issuer(
                org.springframework.security.oauth2.server.authorization.context
                    .AuthorizationServerContextHolder.getContext()
                    .getIssuer())
            .audience(audience)
            .scopes(values -> values.addAll(effective.stream().sorted().toList()))
            .claim("principal_type", "service")
            .claim("permissions", List.of())
            .claim("data_scopes", Map.of())
            .claim("allowed_tenant_ids", tenants.stream().map(Object::toString).sorted().toList());
    if (tenants.size() == 1) claims.claim("tenant_id", tenants.iterator().next().toString());
    return response(request, claims.build());
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> values)) return List.of();
    var result = new ArrayList<String>();
    for (Object element : values) {
      if (!(element instanceof String text)) return List.of();
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static Authentication response(
      OAuth2TokenIntrospectionAuthenticationToken request, OAuth2TokenIntrospection claims) {
    return new OAuth2TokenIntrospectionAuthenticationToken(
        request.getToken(), (Authentication) request.getPrincipal(), claims);
  }

  private static OAuth2ClientAuthenticationToken authenticated(Authentication value) {
    if (!(value.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()
        || client.getRegisteredClient() == null) throw error("invalid_client");
    return client;
  }

  private void audit(String action, String result, String client, String code, String request) {
    audits.protocolEvent(action, result, client, code, request);
  }

  private static OAuth2AuthenticationException error(String code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code));
  }
}
