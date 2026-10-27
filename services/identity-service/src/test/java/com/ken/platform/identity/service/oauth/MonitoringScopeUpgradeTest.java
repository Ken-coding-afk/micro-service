package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitoringScopeUpgradeTest {
  private ClientPolicyRules.Definition value(Set<String> scopes, String callback) {
    return new ClientPolicyRules.Definition(
        "identity-admin-platform",
        "平台身份管理",
        PrincipalType.PLATFORM,
        null,
        Set.of(),
        Set.of("identity-service"),
        scopes,
        Set.of(callback),
        Set.of("https://app.example/logout"),
        false,
        true,
        Status.ACTIVE);
  }

  @Test
  void migrationAllowsOnlyOneExplicitScopeAddition() {
    var before = value(Set.of("profile", "platform.audit"), "https://app.example/callback");
    var after =
        value(
            Set.of("profile", "platform.audit", "platform.monitoring"),
            "https://app.example/callback");
    assertThat(ClientManagementService.monitoringScopeUpgrade(before, after)).isTrue();
    assertThat(ClientManagementService.monitoringScopeUpgrade(after, after)).isFalse();
    assertThat(
            ClientManagementService.monitoringScopeUpgrade(
                before, value(after.scopes(), "https://other.example/callback")))
        .isFalse();
    assertThat(
            ClientManagementService.monitoringScopeUpgrade(
                before, value(Set.of("platform.monitoring"), "https://app.example/callback")))
        .isFalse();
  }
}
