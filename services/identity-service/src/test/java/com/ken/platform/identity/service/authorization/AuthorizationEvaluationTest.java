package com.ken.platform.identity.service.authorization;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.security.DataScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationEvaluationTest {
  private final Instant now = Instant.parse("2026-10-26T00:00:00Z");
  private final AuthorizationEvaluator evaluator =
      new AuthorizationEvaluator(Clock.fixed(now, ZoneOffset.UTC));
  private final List<AuthorizationEvaluator.Permission> catalog =
      List.of(
          new AuthorizationEvaluator.Permission(
              "document:read",
              "document-service",
              "documents.read",
              Set.of(DataScope.SELF, DataScope.TENANT),
              AuthorizationEvaluator.PermissionStatus.ACTIVE),
          new AuthorizationEvaluator.Permission(
              "document:write",
              "document-service",
              "documents.write",
              Set.of(DataScope.SELF, DataScope.TENANT),
              AuthorizationEvaluator.PermissionStatus.ACTIVE));
  private final List<AuthorizationEvaluator.RoleGrant> grants =
      List.of(
          new AuthorizationEvaluator.RoleGrant(
              UUID.randomUUID(), "document:read", DataScope.SELF, true, 1),
          new AuthorizationEvaluator.RoleGrant(
              UUID.randomUUID(), "document:write", DataScope.TENANT, true, 1));

  private AuthorizationEvaluator.ApplicationBoundary boundary(
      Set<String> client, Set<String> token, Set<String> audiences) {
    return new AuthorizationEvaluator.ApplicationBoundary(
        client,
        token,
        audiences,
        audiences,
        "documents",
        Map.of("document-service", "documents"),
        Set.of());
  }

  @Test
  void broaderCurrentPolicyCannotExpandPreviouslyIssuedScopes() {
    var result =
        evaluator.evaluate(
            catalog,
            grants,
            boundary(
                Set.of("documents.read", "documents.write"),
                Set.of("documents.read"),
                Set.of("documents")));
    assertThat(result.permissions()).containsExactly("document:read");
    assertThat(result.dataScopes()).containsOnlyKeys("document:read");
    assertThat(result.decisions().get("document:write").reasons())
        .containsExactly(AuthorizationEvaluator.Reason.TOKEN_SCOPE_LIMITED);
    assertThat(result.asOf()).isEqualTo(now);
    assertThat(result.view()).isEqualTo("APPLICATION_EFFECTIVE");
  }

  @Test
  void narrowerCurrentClientPolicyImmediatelyRestrictsOldToken() {
    var result =
        evaluator.evaluate(
            catalog,
            grants,
            boundary(
                Set.of("documents.write"),
                Set.of("documents.read", "documents.write"),
                Set.of("documents")));
    assertThat(result.permissions()).containsExactly("document:write");
    assertThat(result.dataScopes()).doesNotContainKey("document:read");
    assertThat(result.decisions().get("document:read").reasons())
        .containsExactly(AuthorizationEvaluator.Reason.CLIENT_SCOPE_LIMITED);
    assertThat(evaluator.evaluate(catalog, grants, null).permissions()).hasSize(2);
  }

  @Test
  void wrongAudienceAndWildcardScopesDoNotAuthorizeAnything() {
    var result =
        evaluator.evaluate(catalog, grants, boundary(Set.of("*"), Set.of("*"), Set.of("another")));
    assertThat(result.permissions()).isEmpty();
    assertThat(result.dataScopes()).isEmpty();
    assertThat(result.decisions().get("document:read").reasons())
        .contains(
            AuthorizationEvaluator.Reason.CLIENT_SCOPE_LIMITED,
            AuthorizationEvaluator.Reason.TOKEN_SCOPE_LIMITED,
            AuthorizationEvaluator.Reason.AUDIENCE_MISMATCH);
  }

  @Test
  void tenantManagementCapabilityIsSeparateFromRoleGrant() {
    var permission =
        new AuthorizationEvaluator.Permission(
            "identity:application:manage",
            "identity-service",
            "identity.applications",
            Set.of(DataScope.TENANT),
            AuthorizationEvaluator.PermissionStatus.ACTIVE);
    var role =
        new AuthorizationEvaluator.RoleGrant(
            UUID.randomUUID(), permission.code(), DataScope.TENANT, true, 1);
    var client =
        new AuthorizationEvaluator.ApplicationBoundary(
            Set.of("identity.applications"),
            Set.of("identity.applications"),
            Set.of("identity"),
            Set.of("identity"),
            "identity",
            Map.of("identity-service", "identity"),
            Set.of());
    var result = evaluator.evaluate(List.of(permission), List.of(role), client);
    assertThat(result.permissions()).isEmpty();
    assertThat(result.decisions().get(permission.code()).reasons())
        .containsExactly(AuthorizationEvaluator.Reason.TENANT_CAPABILITY_DISABLED);
  }

  @Test
  void disabledPermissionDropsScopeButDeprecationPreservesExistingGrant() {
    var disabled =
        new AuthorizationEvaluator.Permission(
            "document:read",
            "document-service",
            "documents.read",
            Set.of(DataScope.SELF),
            AuthorizationEvaluator.PermissionStatus.DISABLED);
    assertThat(evaluator.evaluate(List.of(disabled), grants, null).dataScopes()).isEmpty();
    var deprecated =
        new AuthorizationEvaluator.Permission(
            "document:read",
            "document-service",
            "documents.read",
            Set.of(DataScope.SELF),
            AuthorizationEvaluator.PermissionStatus.DEPRECATED);
    assertThat(evaluator.evaluate(List.of(deprecated), grants, null).permissions())
        .containsExactly("document:read");
  }
}
