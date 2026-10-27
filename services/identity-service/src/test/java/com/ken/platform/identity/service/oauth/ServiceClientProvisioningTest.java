package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.oauth.ClientPolicyRules.Definition;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ServiceClientProvisioningTest {
  @Test
  void rerunPreservesDisabledClientTenantAndReducedScopesWithoutWriting() {
    var store = mock(ClientStore.class);
    var rules = mock(ClientPolicyRules.class);
    UUID tenant = UUID.randomUUID();
    var proposed =
        new Definition(
            "sample-swagger",
            "Swagger",
            PrincipalType.USER,
            null,
            Set.of(),
            Set.of("sample"),
            Set.of("profile", "items.write"),
            Set.of("http://localhost:48083/docs/oauth2-redirect"),
            Set.of(),
            true,
            false,
            Status.ACTIVE);
    when(rules.validate(proposed)).thenReturn(proposed);
    var client =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("sample-swagger")
            .clientName("管理员名称")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:49000/docs/oauth2-redirect")
            .scope("profile")
            .build();
    when(store.byClientId("sample-swagger"))
        .thenReturn(
            new ClientStore.Snapshot(
                client,
                new ClientPolicyDO(
                    PrincipalType.USER,
                    null,
                    Set.of(tenant),
                    Set.of("sample"),
                    Status.DISABLED,
                    8)));
    var service =
        new ClientManagementService(
            mock(PlatformTransactionManager.class),
            store,
            rules,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> service.provision(proposed, null))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var actual = service.provision(proposed, null);
      assertThat(actual.definition().allowedTenantIds()).containsExactly(tenant);
      assertThat(actual.definition().scopes()).containsExactly("profile");
      assertThat(actual.definition().status()).isEqualTo(Status.DISABLED);
      assertThat(actual.definition().redirectUris())
          .containsExactly("http://localhost:49000/docs/oauth2-redirect");
      assertThat(actual.version()).isEqualTo(8);
      verify(store).byClientId("sample-swagger");
      verifyNoMoreInteractions(store);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }
}
