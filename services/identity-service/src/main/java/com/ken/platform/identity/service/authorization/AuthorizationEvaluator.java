package com.ken.platform.identity.service.authorization;

import com.ken.platform.foundation.security.DataScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 唯一授权计算器：角色并集之后与客户端、签发上限、目标 audience、租户能力取交集。 */
@Service
public final class AuthorizationEvaluator {
  public enum Reason {
    ALLOWED,
    NO_ROLE_GRANT,
    ROLE_DISABLED,
    PERMISSION_DISABLED,
    INVALID_DATA_SCOPE,
    CLIENT_SCOPE_LIMITED,
    TOKEN_SCOPE_LIMITED,
    AUDIENCE_MISMATCH,
    TENANT_CAPABILITY_DISABLED
  }

  public enum PermissionStatus {
    ACTIVE,
    DEPRECATED,
    DISABLED
  }

  public record Permission(
      String code,
      String ownerService,
      String oauthScope,
      Set<DataScope> supportedScopes,
      PermissionStatus status) {
    public Permission {
      supportedScopes = Set.copyOf(supportedScopes);
    }
  }

  public record RoleGrant(
      UUID roleId,
      String permissionCode,
      DataScope dataScope,
      boolean roleActive,
      long authorizationVersion) {}

  public record ApplicationBoundary(
      Set<String> clientScopes,
      Set<String> tokenScopes,
      Set<String> clientAudiences,
      Set<String> tokenAudiences,
      String targetAudience,
      Map<String, String> serviceAudiences,
      Set<String> enabledCapabilities) {
    public ApplicationBoundary {
      clientScopes = Set.copyOf(clientScopes);
      tokenScopes = Set.copyOf(tokenScopes);
      clientAudiences = Set.copyOf(clientAudiences);
      tokenAudiences = Set.copyOf(tokenAudiences);
      serviceAudiences = Map.copyOf(serviceAudiences);
      enabledCapabilities = Set.copyOf(enabledCapabilities);
      if (targetAudience == null || targetAudience.isBlank()) {
        throw new IllegalArgumentException("应用权限计算必须指定已登记的目标 audience");
      }
    }
  }

  public record Decision(
      boolean allowed, DataScope dataScope, List<RoleGrant> sources, List<Reason> reasons) {
    public Decision {
      sources = List.copyOf(sources);
      reasons = List.copyOf(reasons);
    }
  }

  public record Evaluation(String view, Instant asOf, Map<String, Decision> decisions) {
    public Evaluation {
      decisions = Map.copyOf(decisions);
    }

    public Set<String> permissions() {
      return decisions.entrySet().stream()
          .filter(entry -> entry.getValue().allowed())
          .map(Map.Entry::getKey)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Map<String, DataScope> dataScopes() {
      Map<String, DataScope> result = new TreeMap<>();
      decisions.forEach(
          (code, decision) -> {
            if (decision.allowed() && decision.dataScope() != null) {
              result.put(code, decision.dataScope());
            }
          });
      return Map.copyOf(result);
    }
  }

  private final Clock clock;

  public AuthorizationEvaluator(Clock clock) {
    this.clock = clock;
  }

  public Evaluation evaluate(
      List<Permission> catalog, List<RoleGrant> grants, ApplicationBoundary boundary) {
    Map<String, Decision> result = new TreeMap<>();
    for (Permission permission : catalog) {
      if (result.containsKey(permission.code())) {
        throw new IllegalArgumentException("权限目录包含重复编码");
      }
      List<RoleGrant> sources =
          grants.stream()
              .filter(grant -> grant.permissionCode().equals(permission.code()))
              .toList();
      List<RoleGrant> active = sources.stream().filter(RoleGrant::roleActive).toList();
      EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
      if (sources.isEmpty()) {
        reasons.add(Reason.NO_ROLE_GRANT);
      } else if (active.isEmpty()) {
        reasons.add(Reason.ROLE_DISABLED);
      }
      if (permission.status() == PermissionStatus.DISABLED) {
        reasons.add(Reason.PERMISSION_DISABLED);
      }
      List<RoleGrant> valid = new ArrayList<>();
      for (RoleGrant grant : active) {
        if (grant.dataScope() == null
            ? !permission.supportedScopes().isEmpty()
            : !permission.supportedScopes().contains(grant.dataScope())) {
          reasons.add(Reason.INVALID_DATA_SCOPE);
        } else {
          valid.add(grant);
        }
      }
      if (boundary != null) {
        if (!boundary.clientScopes().contains(permission.oauthScope())) {
          reasons.add(Reason.CLIENT_SCOPE_LIMITED);
        }
        if (!boundary.tokenScopes().contains(permission.oauthScope())) {
          reasons.add(Reason.TOKEN_SCOPE_LIMITED);
        }
        if (!boundary
                .targetAudience()
                .equals(boundary.serviceAudiences().get(permission.ownerService()))
            || !boundary.clientAudiences().contains(boundary.targetAudience())
            || !boundary.tokenAudiences().contains(boundary.targetAudience())) {
          reasons.add(Reason.AUDIENCE_MISMATCH);
        }
        String capability = requiredCapability(permission.code());
        if (capability != null && !boundary.enabledCapabilities().contains(capability)) {
          reasons.add(Reason.TENANT_CAPABILITY_DISABLED);
        }
      }
      boolean allowed = reasons.isEmpty() && !valid.isEmpty();
      DataScope scope = null;
      if (allowed) {
        if (!permission.supportedScopes().isEmpty()) {
          scope =
              valid.stream().anyMatch(grant -> grant.dataScope() == DataScope.TENANT)
                  ? DataScope.TENANT
                  : DataScope.SELF;
        }
        reasons.add(Reason.ALLOWED);
      }
      result.put(permission.code(), new Decision(allowed, scope, sources, List.copyOf(reasons)));
    }
    return new Evaluation(
        boundary == null ? "ROLE_GRANTS" : "APPLICATION_EFFECTIVE", clock.instant(), result);
  }

  /** 固定身份管理能力的委派门槛；不允许业务清单自行声明任意管理能力。 */
  private static String requiredCapability(String code) {
    if (code.startsWith("identity:application:")) {
      return "APPLICATION_MANAGEMENT";
    }
    if (code.startsWith("identity:provider:")) {
      return "PROVIDER_MANAGEMENT";
    }
    if (code.startsWith("identity:security:")) {
      return "SECURITY_POLICY_MANAGEMENT";
    }
    return null;
  }
}
