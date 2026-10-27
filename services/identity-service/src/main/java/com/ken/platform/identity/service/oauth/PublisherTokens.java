package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只接受已有授权服务器签发的项目级目录发布身份，不接受业务用户或机器身份。 */
@Service
public class PublisherTokens {
  private final ClientStore clients;
  private final ProtocolAuthorizations authorizations;
  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;
  private final AuthorizationServerSettings settings;
  private final Clock clock;

  public PublisherTokens(
      ClientStore clients,
      ProtocolAuthorizations authorizations,
      PlatformTransactionManager manager,
      JdbcTemplate jdbc,
      AuthorizationServerSettings settings,
      Clock clock) {
    this.clients = clients;
    this.authorizations = authorizations;
    this.transactions = new TransactionTemplate(manager);
    this.jdbc = jdbc;
    this.settings = settings;
    this.clock = clock;
  }

  public CurrentPrincipal principal(String token) {
    return transactions.execute(
        status -> {
          jdbc.queryForObject(
              "SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR SHARE", Long.class);
          var observed = authorizations.observeByToken(token, OAuth2TokenType.ACCESS_TOKEN);
          if (observed == null) throw invalid();
          var current = clients.lockSnapshot(observed.getRegisteredClientId());
          var authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
          if (current == null
              || authorization == null
              || authorization.getAccessToken() == null
              || !authorization.getAccessToken().isActive()
              || !"service".equals(authorization.getAttribute(ProtocolAuthorizations.TYPE))
              || authorization.getAttribute(ProtocolAuthorizations.REVOKED) != null
              || !List.of("identity-service")
                  .equals(authorization.getAttribute(ProtocolAuthorizations.AUDIENCES))
              || !List.of().equals(authorization.getAttribute(ProtocolAuthorizations.TENANTS))
              || current.policy().status() != ClientPolicyDO.Status.ACTIVE
              || current.policy().principalType() != ClientPolicyDO.PrincipalType.SERVICE
              || current.policy().ownerTenantId() != null
              || !current.policy().allowedTenantIds().isEmpty()
              || !current.policy().audiences().equals(Set.of("identity-service"))) throw invalid();
          var scopes = new HashSet<>(authorization.getAccessToken().getToken().getScopes());
          scopes.retainAll(current.client().getScopes());
          scopes.removeIf(scope -> !scope.startsWith("catalog.publish:"));
          if (scopes.isEmpty()) throw invalid();
          var access = authorization.getAccessToken().getToken();
          return CurrentPrincipal.parse(
              Map.ofEntries(
                  Map.entry("active", true),
                  Map.entry("iss", settings.getIssuer()),
                  Map.entry("aud", List.of("identity-service")),
                  Map.entry("exp", access.getExpiresAt().getEpochSecond()),
                  Map.entry("iat", access.getIssuedAt().getEpochSecond()),
                  Map.entry("sub", current.client().getClientId()),
                  Map.entry("client_id", current.client().getClientId()),
                  Map.entry("principal_type", "service"),
                  Map.entry("scope", String.join(" ", scopes)),
                  Map.entry("permissions", List.of()),
                  Map.entry("data_scopes", Map.of()),
                  Map.entry("allowed_tenant_ids", List.of())),
              settings.getIssuer(),
              "identity-service",
              clock);
        });
  }

  public void require(String token, String service) {
    try {
      if (!principal(token).scopes().contains("catalog.publish:" + service))
        throw new com.ken.platform.foundation.http.PlatformException(
            "CATALOG_PUBLISH_FORBIDDEN", "发布身份未授权该服务", 403);
    } catch (BadOpaqueTokenException invalid) {
      throw new com.ken.platform.foundation.http.PlatformException(
          "INVALID_CREDENTIALS", "发布身份已失效", 401);
    }
  }

  private static BadOpaqueTokenException invalid() {
    return new BadOpaqueTokenException("目录发布凭证无效");
  }
}
