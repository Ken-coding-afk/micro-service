package com.ken.platform.identity.service.security;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 项目策略直接读身份库；历史租户差异须先统一，不能静默放宽。 */
@Service
public final class SecurityPolicies {
  public record Effective(
      int passwordMinChars,
      int loginFailureThreshold,
      int loginFailureWindowSeconds,
      int loginLockSeconds,
      int loginIpAttemptLimit,
      int loginIpWindowSeconds,
      int sessionAbsoluteSeconds,
      int sessionIdleSeconds,
      int recentAuthSeconds,
      long platformVersion,
      Long tenantVersion) {}

  private final SecurityPolicyMapper mapper;

  public SecurityPolicies(SecurityPolicyMapper mapper) {
    this.mapper = mapper;
  }

  public Effective current(UUID tenant) {
    var rows = mapper.current(tenant);
    var base =
        rows.stream()
            .filter(row -> row.ownerType().equals("PLATFORM"))
            .findFirst()
            .orElseThrow(SecurityPolicies::unavailable);
    var override =
        rows.stream()
            .filter(
                row ->
                    row.ownerType().equals("TENANT")
                        && tenant != null
                        && tenant.equals(row.ownerTenantId()))
            .findFirst()
            .orElse(null);
    if (rows.size() != (override == null ? 1 : 2)) {
      throw unavailable();
    }
    requireCovered(base, override);
    return effective(base, null);
  }

  /** 历史覆盖只用于升级保护，不再作为当前租户动态策略。 */
  public static void requireCovered(SecurityPolicyMapper.Row base, SecurityPolicyMapper.Row old) {
    effective(base, null);
    if (old == null) return;
    Integer[] project = {
      base.passwordMinChars(),
      base.loginFailureThreshold(),
      base.loginFailureWindowSeconds(),
      base.loginLockSeconds(),
      base.sessionAbsoluteSeconds(),
      base.sessionIdleSeconds(),
      base.recentAuthSeconds()
    };
    Integer[] previous = {
      old.passwordMinChars(),
      old.loginFailureThreshold(),
      old.loginFailureWindowSeconds(),
      old.loginLockSeconds(),
      old.sessionAbsoluteSeconds(),
      old.sessionIdleSeconds(),
      old.recentAuthSeconds()
    };
    boolean[] higherIsStricter = {true, false, true, true, false, false, false};
    if (old.loginIpAttemptLimit() != null || old.loginIpWindowSeconds() != null)
      throw unavailable();
    for (int i = 0; i < previous.length; i++) {
      if (previous[i] != null
          && (higherIsStricter[i] ? project[i] < previous[i] : project[i] > previous[i])) {
        throw new PlatformException(
            "SECURITY_POLICY_UNIFICATION_REQUIRED", "项目策略弱于历史租户策略，须先统一后再切换", 409);
      }
    }
  }

  static Effective effective(SecurityPolicyMapper.Row base, SecurityPolicyMapper.Row override) {
    int min =
        higher(base.passwordMinChars(), override == null ? null : override.passwordMinChars());
    int failures =
        lower(
            base.loginFailureThreshold(),
            override == null ? null : override.loginFailureThreshold());
    int window =
        higher(
            base.loginFailureWindowSeconds(),
            override == null ? null : override.loginFailureWindowSeconds());
    int lock =
        higher(base.loginLockSeconds(), override == null ? null : override.loginLockSeconds());
    int absolute =
        lower(
            base.sessionAbsoluteSeconds(),
            override == null ? null : override.sessionAbsoluteSeconds());
    int idle =
        lower(base.sessionIdleSeconds(), override == null ? null : override.sessionIdleSeconds());
    int recent =
        lower(base.recentAuthSeconds(), override == null ? null : override.recentAuthSeconds());
    if (override != null
            && (override.loginIpAttemptLimit() != null || override.loginIpWindowSeconds() != null)
        || min < 15
        || min > 32
        || failures < 1
        || failures > 10
        || window < 900
        || window > 3600
        || lock < 900
        || lock > 3600
        || absolute < 300
        || absolute > 28800
        || idle < 60
        || idle > 1800
        || idle > absolute
        || recent < 60
        || recent > 300
        || base.loginIpAttemptLimit() == null
        || base.loginIpAttemptLimit() < 1
        || base.loginIpAttemptLimit() > 60
        || base.loginIpWindowSeconds() == null
        || base.loginIpWindowSeconds() < 60
        || base.loginIpWindowSeconds() > 300) {
      throw unavailable();
    }
    return new Effective(
        min,
        failures,
        window,
        lock,
        base.loginIpAttemptLimit(),
        base.loginIpWindowSeconds(),
        absolute,
        idle,
        recent,
        base.version(),
        override == null ? null : override.version());
  }

  private static int higher(Integer base, Integer override) {
    if (base == null || override != null && override < base) {
      throw unavailable();
    }
    return override == null ? base : override;
  }

  private static int lower(Integer base, Integer override) {
    if (base == null || override != null && override > base) {
      throw unavailable();
    }
    return override == null ? base : override;
  }

  private static PlatformException unavailable() {
    return new PlatformException("SECURITY_POLICY_UNAVAILABLE", "安全策略当前不可用", 503);
  }
}
