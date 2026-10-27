package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.service.oauth.ClientPolicyRules.Definition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPolicyRulesTest {
  private final UUID tenant = UUID.randomUUID();

  private Definition client(String callback, boolean publicClient, boolean firstParty) {
    return new Definition(
        "test-client",
        "客户端",
        PrincipalType.USER,
        tenant,
        Set.of(tenant),
        Set.of("document-service"),
        Set.of("openid", "document.read"),
        Set.of(callback),
        Set.of(),
        publicClient,
        firstParty,
        Status.ACTIVE);
  }

  @Test
  void prodRequiresExactHttpsAndLocalOnlyAllowsExactLoopbackException() {
    var prod = new ClientPolicyRules("prod");
    assertThat(
            prod.validate(client("https://client.example/callback?fixed=1", true, false))
                .publicClient())
        .isTrue();
    for (String callback :
        List.of(
            "http://client.example/callback",
            "https://client.example/*",
            "https://u:p@client.example/callback",
            "https://client.example/callback#x",
            "http://127.0.0.1.evil/callback",
            "https://client.example:99999/callback")) {
      assertThatThrownBy(() -> prod.validate(client(callback, true, false)))
          .isInstanceOf(PlatformException.class);
    }
    var local = new ClientPolicyRules("local");
    for (String callback :
        List.of(
            "http://localhost/callback",
            "http://127.0.0.1:18080/callback",
            "http://[::1]:18080/callback")) {
      local.validate(client(callback, true, false));
    }
    assertThatThrownBy(() -> prod.validate(client("http://localhost/callback", true, false)))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void publicClientsCannotSkipConsentAndMachineHasNoBrowserOrOidc() {
    var rules = new ClientPolicyRules("local");
    assertThatThrownBy(() -> rules.validate(client("https://a.example/cb", true, true)))
        .isInstanceOf(PlatformException.class);
    rules.validate(client("https://a.example/cb", false, true));
    var machine =
        new Definition(
            "machine",
            "机器",
            PrincipalType.SERVICE,
            null,
            Set.of(),
            Set.of("identity-service"),
            Set.of("catalog.publish:document-service"),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    rules.validate(machine);
    var invalid =
        new Definition(
            machine.clientId(),
            machine.name(),
            machine.principalType(),
            null,
            Set.of(),
            machine.audiences(),
            Set.of("openid"),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    assertThatThrownBy(() -> rules.validate(invalid)).isInstanceOf(PlatformException.class);
  }

  @Test
  void tenantOwnerCannotExpandAndEmptyProjectTenantSetIsExplicitlyEmpty() {
    var rules = new ClientPolicyRules("prod");
    var value = client("https://a.example/cb", false, false);
    var expanded =
        new Definition(
            value.clientId(),
            value.name(),
            value.principalType(),
            tenant,
            Set.of(tenant, UUID.randomUUID()),
            value.audiences(),
            value.scopes(),
            value.redirectUris(),
            value.logoutUris(),
            false,
            false,
            value.status());
    assertThatThrownBy(() -> rules.validate(expanded)).isInstanceOf(PlatformException.class);
    var project =
        new Definition(
            value.clientId(),
            value.name(),
            value.principalType(),
            null,
            Set.of(),
            value.audiences(),
            value.scopes(),
            value.redirectUris(),
            value.logoutUris(),
            false,
            true,
            value.status());
    assertThat(rules.validate(project).allowedTenantIds()).isEmpty();
  }

  @Test
  void nameMatchesProtocolColumnAndExplicitTenantSetHasNoInventedQuota() {
    var rules = new ClientPolicyRules("prod");
    var value = client("https://a.example/cb", false, true);
    Set<UUID> tenants =
        java.util.stream.IntStream.range(0, 1001)
            .mapToObj(number -> new UUID(0, number + 1L))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    var large =
        new Definition(
            value.clientId(),
            "客".repeat(200),
            value.principalType(),
            null,
            tenants,
            value.audiences(),
            value.scopes(),
            value.redirectUris(),
            value.logoutUris(),
            value.publicClient(),
            value.firstParty(),
            value.status());
    assertThat(rules.validate(large).allowedTenantIds()).hasSize(1001);
    for (String name : List.of("客".repeat(201), "<script>", "name\n", "\uD800")) {
      var invalid =
          new Definition(
              value.clientId(),
              name,
              value.principalType(),
              null,
              tenants,
              value.audiences(),
              value.scopes(),
              value.redirectUris(),
              value.logoutUris(),
              value.publicClient(),
              value.firstParty(),
              value.status());
      assertThatThrownBy(() -> rules.validate(invalid)).isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void introspectionCredentialHasExactlyOneDedicatedTarget() {
    var rules = new ClientPolicyRules("local");
    for (Set<String> scopes :
        List.of(
            Set.of("internal.introspect:identity-service", "internal.introspect:document-service"),
            Set.of("internal.introspect:identity-service", "catalog.publish:document-service"))) {
      var value =
          new Definition(
              "introspector",
              "内省",
              PrincipalType.SERVICE,
              null,
              Set.of(),
              Set.of("identity-service"),
              scopes,
              Set.of(),
              Set.of(),
              false,
              false,
              Status.ACTIVE);
      assertThatThrownBy(() -> rules.validate(value)).isInstanceOf(PlatformException.class);
    }
  }
}
