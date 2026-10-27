package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PublisherTokensTest {
  private final ClientStore store = mock(ClientStore.class);
  private final ProtocolAuthorizations records = mock(ProtocolAuthorizations.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final RegisteredClient client =
      RegisteredClient.withId(UUID.randomUUID().toString())
          .clientId("publisher")
          .clientSecret("{bcrypt}unused")
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
          .scope("catalog.publish:sample-service")
          .build();
  private final OAuth2Authorization original =
      OAuth2Authorization.withRegisteredClient(client)
          .principalName("publisher")
          .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
          .attribute(ProtocolAuthorizations.TYPE, "service")
          .attribute(ProtocolAuthorizations.AUDIENCES, List.of("identity-service"))
          .attribute(ProtocolAuthorizations.TENANTS, List.of())
          .accessToken(
              new OAuth2AccessToken(
                  OAuth2AccessToken.TokenType.BEARER,
                  "issued-token",
                  Instant.now().minusSeconds(1),
                  Instant.now().plusSeconds(300),
                  Set.of("catalog.publish:sample-service")))
          .build();

  private PublisherTokens setup() {
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(records.observeByToken("issued-token", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(original);
    when(records.findByToken("issued-token", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(original);
    snapshot(ClientPolicyDO.Status.ACTIVE, Set.of());
    return new PublisherTokens(
        store,
        records,
        manager,
        mock(JdbcTemplate.class),
        AuthorizationServerSettings.builder().issuer("http://localhost:18080").build(),
        Clock.systemUTC());
  }

  private void snapshot(ClientPolicyDO.Status status, Set<UUID> tenants) {
    when(store.lockSnapshot(client.getId()))
        .thenReturn(
            new ClientStore.Snapshot(
                client,
                new ClientPolicyDO(
                    ClientPolicyDO.PrincipalType.SERVICE,
                    null,
                    tenants,
                    Set.of("identity-service"),
                    status,
                    0)));
  }

  @Test
  void validPublisherHasOnlyItsIssuedScope() {
    var tokens = setup();
    var principal = tokens.principal("issued-token");
    assertThat(principal.scopes()).containsExactly("catalog.publish:sample-service");
    assertThat(principal.tenantId()).isNull();
    assertThat(principal.permissions()).isEmpty();
    assertThatThrownBy(() -> tokens.require("issued-token", "other-service"))
        .hasMessage("发布身份未授权该服务");
  }

  @Test
  void revokedDisabledTenantBoundAndUnknownTokensAreRejected() {
    var tokens = setup();
    assertThatThrownBy(() -> tokens.principal("unknown"))
        .isInstanceOf(BadOpaqueTokenException.class);
    snapshot(ClientPolicyDO.Status.DISABLED, Set.of());
    assertThatThrownBy(() -> tokens.principal("issued-token"))
        .isInstanceOf(BadOpaqueTokenException.class);
    snapshot(ClientPolicyDO.Status.ACTIVE, Set.of(UUID.randomUUID()));
    assertThatThrownBy(() -> tokens.principal("issued-token"))
        .isInstanceOf(BadOpaqueTokenException.class);
    snapshot(ClientPolicyDO.Status.ACTIVE, Set.of());
    when(records.findByToken("issued-token", OAuth2TokenType.ACCESS_TOKEN))
        .thenReturn(
            OAuth2Authorization.from(original)
                .attribute(ProtocolAuthorizations.REVOKED, Instant.now().toString())
                .build());
    assertThatThrownBy(() -> tokens.principal("issued-token"))
        .isInstanceOf(BadOpaqueTokenException.class);
  }

  @Test
  void currentScopeReductionCannotUsePreviouslyIssuedGrant() {
    var tokens = setup();
    var reduced =
        RegisteredClient.from(client)
            .scopes(
                scopes -> {
                  scopes.clear();
                  scopes.add("catalog.publish:other-service");
                })
            .build();
    var old = store.lockSnapshot(client.getId());
    when(store.lockSnapshot(client.getId()))
        .thenReturn(new ClientStore.Snapshot(reduced, old.policy()));
    assertThatThrownBy(() -> tokens.principal("issued-token"))
        .isInstanceOf(BadOpaqueTokenException.class);
  }
}
