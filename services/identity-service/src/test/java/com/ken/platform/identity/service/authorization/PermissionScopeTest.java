package com.ken.platform.identity.service.authorization;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.security.DataScope;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PermissionScopeTest {
  private final AuthorizationEvaluator evaluator = new AuthorizationEvaluator(Clock.systemUTC());

  private AuthorizationEvaluator.Permission permission(String code, Set<DataScope> scopes) {
    return new AuthorizationEvaluator.Permission(
        code,
        "document-service",
        code.replace(':', '.'),
        scopes,
        AuthorizationEvaluator.PermissionStatus.ACTIVE);
  }

  private AuthorizationEvaluator.RoleGrant grant(
      String permission, DataScope scope, boolean active) {
    return new AuthorizationEvaluator.RoleGrant(UUID.randomUUID(), permission, scope, active, 1);
  }

  @Test
  void sameOperationUsesUnionAndDifferentOperationDoesNotWidenScope() {
    var catalog =
        List.of(
            permission("document:read", Set.of(DataScope.SELF, DataScope.TENANT)),
            permission("document:delete", Set.of(DataScope.SELF, DataScope.TENANT)));
    var first =
        evaluator.evaluate(
            catalog,
            List.of(
                grant("document:read", DataScope.SELF, true),
                grant("document:delete", DataScope.TENANT, true)),
            null);
    assertThat(first.dataScopes())
        .containsEntry("document:read", DataScope.SELF)
        .containsEntry("document:delete", DataScope.TENANT);
    var union =
        evaluator.evaluate(
            catalog,
            List.of(
                grant("document:read", DataScope.SELF, true),
                grant("document:read", DataScope.TENANT, true)),
            null);
    assertThat(union.dataScopes())
        .containsEntry("document:read", DataScope.TENANT)
        .doesNotContainKey("document:delete");
  }

  @Test
  void nullMeansNoObjectScopeAndNeverGrantsTenantRead() {
    var result =
        evaluator.evaluate(
            List.of(
                permission("document:submit", Set.of()),
                permission("document:read", Set.of(DataScope.SELF, DataScope.TENANT))),
            List.of(grant("document:submit", null, true), grant("document:read", null, true)),
            null);
    assertThat(result.permissions()).containsExactly("document:submit");
    assertThat(result.dataScopes()).isEmpty();
    assertThat(result.decisions().get("document:read").reasons())
        .contains(AuthorizationEvaluator.Reason.INVALID_DATA_SCOPE);
  }

  @Test
  void disabledRoleAndUnknownPermissionCannotCreateGrant() {
    var result =
        evaluator.evaluate(
            List.of(permission("document:read", Set.of(DataScope.SELF))),
            List.of(
                grant("document:read", DataScope.SELF, false),
                grant("unknown:read", DataScope.TENANT, true)),
            null);
    assertThat(result.permissions()).isEmpty();
    assertThat(result.decisions()).doesNotContainKey("unknown:read");
    assertThat(result.decisions().get("document:read").reasons())
        .containsExactly(AuthorizationEvaluator.Reason.ROLE_DISABLED);
  }
}
