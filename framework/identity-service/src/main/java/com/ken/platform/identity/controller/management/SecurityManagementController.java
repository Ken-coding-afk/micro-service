package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.security.SecurityPolicyManagementService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public final class SecurityManagementController {
  public record PlatformUpdate(Long version, Map<String, Object> values) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的安全策略字段");
    }
  }

  public record View(
      Long version,
      @JsonProperty("platform_version") long platformVersion,
      @JsonProperty("tenant_version") Long tenantVersion,
      Map<String, Integer> values,
      Map<String, Integer> inherited,
      Map<String, Integer> effective) {}

  private final SecurityPolicyManagementService policies;

  public SecurityManagementController(SecurityPolicyManagementService policies) {
    this.policies = policies;
  }

  @GetMapping("/platform/security-policy")
  ResponseEntity<Map<String, Object>> platform() {
    return IdentityApiResponses.ok(view(policies.readPlatform()));
  }

  @PutMapping("/platform/security-policy")
  ResponseEntity<Map<String, Object>> updatePlatform(@RequestBody PlatformUpdate command) {
    return IdentityApiResponses.ok(
        view(policies.updatePlatform(version(command.version()), command.values())));
  }

  private static View view(SecurityPolicyManagementService.State state) {
    var e = state.effective();
    return new View(
        state.version(),
        e.platformVersion(),
        e.tenantVersion(),
        state.values(),
        state.inherited(),
        Map.of(
            "password_min_chars",
            e.passwordMinChars(),
            "login_failure_threshold",
            e.loginFailureThreshold(),
            "login_failure_window_seconds",
            e.loginFailureWindowSeconds(),
            "login_lock_seconds",
            e.loginLockSeconds(),
            "login_ip_attempt_limit",
            e.loginIpAttemptLimit(),
            "login_ip_window_seconds",
            e.loginIpWindowSeconds(),
            "session_absolute_seconds",
            e.sessionAbsoluteSeconds(),
            "session_idle_seconds",
            e.sessionIdleSeconds(),
            "recent_auth_seconds",
            e.recentAuthSeconds()));
  }

  private static long version(Long value) {
    if (value == null || value < 0)
      throw new PlatformException("INVALID_ARGUMENT", "安全策略版本不合法", 400);
    return value;
  }
}
