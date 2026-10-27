package com.ken.platform.identity.service.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.config.SecurityPolicyUpgradeCheck;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityPoliciesTest {
  private final UUID tenant = UUID.randomUUID();
  private final SecurityPolicyMapper mapper = mock(SecurityPolicyMapper.class);
  private final SecurityPolicies policies = new SecurityPolicies(mapper);

  private SecurityPolicyMapper.Row base() {
    return new SecurityPolicyMapper.Row(
        "PLATFORM", null, 15, 10, 900, 900, 60, 60, 28800, 1800, 300, 3);
  }

  private SecurityPolicyMapper.Row old(Integer min) {
    return new SecurityPolicyMapper.Row(
        "TENANT", tenant, min, null, null, null, null, null, null, null, null, 7);
  }

  @Test
  void projectPolicyAppliesToAllTenantsWithoutDynamicOverride() {
    for (var previous : List.of(old(null), old(15))) {
      when(mapper.current(tenant)).thenReturn(List.of(base(), previous));
      var effective = policies.current(tenant);
      assertThat(effective.passwordMinChars()).isEqualTo(15);
      assertThat(effective.tenantVersion()).isNull();
      assertThat(effective.platformVersion()).isEqualTo(3);
    }
  }

  @Test
  void strongerLegacyPolicyBlocksRuntimeAndStartupRatherThanWeakeningProtection() {
    when(mapper.current(tenant)).thenReturn(List.of(base(), old(20)));
    when(mapper.current(null)).thenReturn(List.of(base()));
    when(mapper.tenantOverrides()).thenReturn(List.of(old(20)));
    assertThatThrownBy(() -> policies.current(tenant))
        .isInstanceOfSatisfying(
            PlatformException.class,
            e -> assertThat(e.code()).isEqualTo("SECURITY_POLICY_UNIFICATION_REQUIRED"));
    assertThatThrownBy(() -> new SecurityPolicyUpgradeCheck(mapper).run(null))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void missingDuplicateOrInvalidBaseFailsClosed() {
    for (var rows :
        List.of(
            List.<SecurityPolicyMapper.Row>of(),
            List.of(base(), base()),
            List.of(
                new SecurityPolicyMapper.Row(
                    "PLATFORM", null, 14, 10, 900, 900, 60, 60, 28800, 1800, 300, 3)))) {
      when(mapper.current(tenant)).thenReturn(rows);
      assertThatThrownBy(() -> policies.current(tenant))
          .isInstanceOfSatisfying(
              PlatformException.class,
              e -> assertThat(e.code()).isEqualTo("SECURITY_POLICY_UNAVAILABLE"));
    }
  }

  @Test
  void equivalentLegacyPolicyAllowsStartupWithoutChangingRows() {
    when(mapper.current(null)).thenReturn(List.of(base()));
    when(mapper.tenantOverrides()).thenReturn(List.of(old(15)));
    new SecurityPolicyUpgradeCheck(mapper).run(null);
    verify(mapper).tenantOverrides();
  }
}
