package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.account.AccountCreationService;
import com.ken.platform.identity.service.account.PlatformAccountQueryService;
import com.ken.platform.identity.service.account.PlatformLifecycleService;
import com.ken.platform.identity.service.security.PasswordCredentials;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public final class AccountManagementController {
  public record NewAccount(String username, @JsonProperty("display_name") String displayName) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的账号字段");
    }
  }

  public record NewTenant(
      String code,
      String name,
      @JsonProperty("expires_at") Instant expiresAt,
      @JsonProperty("admin_username") String adminUsername,
      @JsonProperty("admin_display_name") String adminDisplayName) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的租户字段");
    }
  }

  public record AdministratorUpdate(
      Long version,
      @JsonProperty("display_name") String displayName,
      PlatformLifecycleService.Status status) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的管理员字段");
    }
  }

  public record TenantUpdate(
      Long version,
      String name,
      @JsonProperty("expires_at") Instant expiresAt,
      PlatformLifecycleService.Status status) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的租户字段");
    }
  }

  public record IssuedAccount(
      UUID id, @JsonProperty("initial_password") String initialPassword, boolean replayed) {
    @Override
    public String toString() {
      return "IssuedAccount[id=" + id + ", initial_password=REDACTED]";
    }
  }

  public record IssuedTenant(
      @JsonProperty("tenant_id") UUID tenantId,
      @JsonProperty("admin_id") UUID adminId,
      @JsonProperty("initial_password") String initialPassword,
      boolean replayed) {
    @Override
    public String toString() {
      return "IssuedTenant[id=" + tenantId + ", initial_password=REDACTED]";
    }
  }

  public record AdministratorResult(
      UUID id,
      @JsonProperty("display_name") String displayName,
      String status,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      long version) {}

  public record TenantResult(
      UUID id,
      String name,
      String status,
      @JsonProperty("expires_at") Instant expiresAt,
      long version) {}

  private final AccountCreationService creation;
  private final PlatformLifecycleService lifecycle;
  private final PlatformAccountQueryService queries;

  public AccountManagementController(
      AccountCreationService creation,
      PlatformLifecycleService lifecycle,
      PlatformAccountQueryService queries) {
    this.creation = creation;
    this.lifecycle = lifecycle;
    this.queries = queries;
  }

  @PostMapping("/users")
  ResponseEntity<Map<String, Object>> createUser(@RequestBody NewAccount command) {
    var result = creation.createUser(command.username(), command.displayName());
    return IdentityApiResponses.ok(
        new IssuedAccount(result.id(), reveal(result.credential()), result.replayed()));
  }

  @PostMapping("/platform/admins")
  ResponseEntity<Map<String, Object>> createAdministrator(@RequestBody NewAccount command) {
    var result = creation.createPlatformAdministrator(command.username(), command.displayName());
    return IdentityApiResponses.ok(
        new IssuedAccount(result.id(), reveal(result.credential()), result.replayed()));
  }

  @PostMapping("/platform/tenants")
  ResponseEntity<Map<String, Object>> createTenant(@RequestBody NewTenant command) {
    var result =
        creation.createTenant(
            new AccountCreationService.TenantCommand(
                command.code(),
                command.name(),
                command.expiresAt(),
                command.adminUsername(),
                command.adminDisplayName()));
    return IdentityApiResponses.ok(
        new IssuedTenant(
            result.tenantId(), result.adminId(), reveal(result.credential()), result.replayed()));
  }

  @GetMapping("/platform/admins")
  ResponseEntity<Map<String, Object>> administrators(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    return IdentityApiResponses.ok(queries.administrators(page, size, q));
  }

  @GetMapping("/platform/admins/{id}")
  ResponseEntity<Map<String, Object>> administrator(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.administrator(id));
  }

  @GetMapping("/platform/tenants")
  ResponseEntity<Map<String, Object>> tenants(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    return IdentityApiResponses.ok(queries.tenants(page, size, q));
  }

  @GetMapping("/platform/tenants/{id}")
  ResponseEntity<Map<String, Object>> tenant(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.tenant(id));
  }

  @PutMapping("/platform/admins/{id}")
  ResponseEntity<Map<String, Object>> updateAdministrator(
      @PathVariable UUID id, @RequestBody AdministratorUpdate command) {
    var result =
        lifecycle.updateAdministrator(
            id, version(command.version()), command.displayName(), command.status());
    return IdentityApiResponses.ok(
        new AdministratorResult(
            result.id(),
            result.name(),
            result.status(),
            result.mustChangePassword(),
            result.version()));
  }

  @PutMapping("/platform/tenants/{id}")
  ResponseEntity<Map<String, Object>> updateTenant(
      @PathVariable UUID id, @RequestBody TenantUpdate command) {
    var result =
        lifecycle.updateTenant(
            id, version(command.version()), command.name(), command.expiresAt(), command.status());
    return IdentityApiResponses.ok(
        new TenantResult(
            result.id(), result.name(), result.status(), result.expiresAt(), result.version()));
  }

  private static String reveal(PasswordCredentials.OneTimeSecret secret) {
    if (secret == null) return null;
    try (secret) {
      return secret.revealOnce();
    }
  }

  private static long version(Long version) {
    if (version == null || version < 0)
      throw new PlatformException("INVALID_ARGUMENT", "版本号不合法", 400);
    return version;
  }
}
