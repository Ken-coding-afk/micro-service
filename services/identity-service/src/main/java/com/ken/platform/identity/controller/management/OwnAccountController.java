package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.account.OwnAccountService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/me")
public final class OwnAccountController {
  public record ProfileChange(Long version, @JsonProperty("display_name") String displayName) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的个人资料字段");
    }
  }

  public record PasswordChange(
      @JsonProperty("current_password") String current, @JsonProperty("new_password") String next) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的改密字段");
    }

    @Override
    public String toString() {
      return "PasswordChange[REDACTED]";
    }
  }

  private final OwnAccountService accounts;

  public OwnAccountController(OwnAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/profile")
  ResponseEntity<Map<String, Object>> profile(@RequestParam Map<String, String> query) {
    fields(query, Set.of());
    return IdentityApiResponses.ok(accounts.profile());
  }

  @PutMapping("/profile")
  ResponseEntity<Map<String, Object>> profile(
      @RequestParam Map<String, String> query, @RequestBody ProfileChange command) {
    fields(query, Set.of());
    if (command.version() == null || command.version() < 0)
      throw new PlatformException("INVALID_ARGUMENT", "账号版本不合法", 400);
    return IdentityApiResponses.ok(
        accounts.updateProfile(command.version(), command.displayName()));
  }

  @GetMapping("/sessions")
  ResponseEntity<Map<String, Object>> sessions(
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam Map<String, String> query) {
    fields(query, Set.of("page", "size"));
    return IdentityApiResponses.ok(accounts.sessions(page, size));
  }

  @PostMapping("/sessions/{id}:revoke")
  ResponseEntity<Map<String, Object>> revokeSession(
      @PathVariable UUID id,
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Map<String, Object> body) {
    empty(query, body);
    accounts.revokeSession(id);
    return IdentityApiResponses.ok(Map.of("revoked", true));
  }

  @PostMapping("/sessions:sign-out-all")
  ResponseEntity<Map<String, Object>> signOutAll(
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Map<String, Object> body) {
    empty(query, body);
    return IdentityApiResponses.ok(Map.of("revoked", accounts.signOutAll()));
  }

  @GetMapping("/consents")
  ResponseEntity<Map<String, Object>> consents(
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam Map<String, String> query) {
    fields(query, Set.of("page", "size"));
    return IdentityApiResponses.ok(accounts.consents(page, size));
  }

  @PostMapping("/consents/{clientId}:revoke")
  ResponseEntity<Map<String, Object>> revokeConsent(
      @PathVariable String clientId,
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Map<String, Object> body) {
    empty(query, body);
    accounts.revokeConsent(clientId);
    return IdentityApiResponses.ok(Map.of("revoked", true));
  }

  @PostMapping("/password")
  ResponseEntity<Map<String, Object>> password(
      @RequestParam Map<String, String> query, @RequestBody PasswordChange command) {
    fields(query, Set.of());
    if (command.current() == null
        || command.next() == null
        || command.current().length() > 1024
        || command.next().length() > 1024)
      throw new PlatformException("INVALID_ARGUMENT", "密码字段不合法", 400);
    accounts.changePassword(command.current(), command.next());
    return IdentityApiResponses.ok(Map.of("changed", true, "reauthentication_required", true));
  }

  private static void empty(Map<String, String> query, Map<String, Object> body) {
    fields(query, Set.of());
    fields(body, Set.of());
  }

  private static void fields(Map<String, ?> fields, Set<String> allowed) {
    if (fields != null && !allowed.containsAll(fields.keySet()))
      throw new PlatformException("INVALID_ARGUMENT", "请求包含不支持的字段", 400);
  }
}
