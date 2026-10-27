package com.ken.platform.identity.dal.mapper.authorization;

import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.service.authorization.AuthorizationEvaluator;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class AuthorizationRepository {
  private final AuthorizationMapper mapper;

  public AuthorizationRepository(AuthorizationMapper mapper) {
    this.mapper = mapper;
  }

  public List<AuthorizationEvaluator.Permission> permissions() {
    return permissions(false);
  }

  public List<AuthorizationEvaluator.Permission> permissions(boolean platform) {
    return (platform ? mapper.platformPermissions() : mapper.permissions())
        .stream()
            .map(
                row ->
                    new AuthorizationEvaluator.Permission(
                        row.code(),
                        row.ownerService(),
                        row.oauthScope(),
                        scopes(row.supportedDataScopes()),
                        AuthorizationEvaluator.PermissionStatus.valueOf(row.status())))
            .toList();
  }

  public List<AuthorizationEvaluator.RoleGrant> grants(UUID tenantId, UUID userId) {
    return mapper.grants(tenantId, userId).stream()
        .map(
            row ->
                new AuthorizationEvaluator.RoleGrant(
                    row.roleId(),
                    row.permissionCode(),
                    row.dataScope() == null ? null : DataScope.valueOf(row.dataScope()),
                    row.roleActive(),
                    row.authorizationVersion()))
        .toList();
  }

  private static Set<DataScope> scopes(String raw) {
    if (raw == null || raw.isEmpty()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(DataScope::valueOf)
        .collect(Collectors.toUnmodifiableSet());
  }
}
