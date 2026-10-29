package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.permission.ManualPermissionService;
import com.ken.platform.identity.service.permission.PermissionCatalogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/platform")
public final class PermissionManagementController {
  public record Create(
      String code,
      String name,
      @JsonProperty("owner_service") String ownerService,
      @JsonProperty("group_code") String groupCode,
      @JsonProperty("group_name") String groupName,
      @JsonProperty("resource_code") String resourceCode,
      @JsonProperty("resource_name") String resourceName,
      @JsonProperty("action_code") String actionCode,
      String description,
      Integer sort,
      @JsonProperty("supported_data_scopes") Set<DataScope> supportedDataScopes,
      @JsonProperty("oauth_scope") String oauthScope,
      @JsonProperty("catalog_version") Long catalogVersion)
      implements UserGroupController.Command {}

  public record Update(
      String name,
      @JsonProperty("group_code") String groupCode,
      @JsonProperty("group_name") String groupName,
      @JsonProperty("resource_name") String resourceName,
      String description,
      int sort,
      String status,
      @JsonProperty("catalog_version") Long catalogVersion)
      implements UserGroupController.Command {}

  private final ManualPermissionService permissions;

  public PermissionManagementController(ManualPermissionService permissions) {
    this.permissions = permissions;
  }

  @GetMapping("/service-audiences")
  ResponseEntity<Map<String, Object>> services() {
    return IdentityApiResponses.ok(permissions.services());
  }

  @GetMapping("/permissions")
  ResponseEntity<Map<String, Object>> list(
      @RequestParam(name = "owner_service", required = false) String owner,
      @RequestParam(name = "group_code", required = false) String group,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    return IdentityApiResponses.ok(permissions.list(owner, group, source, status, q, page, size));
  }

  @GetMapping("/permissions/{code}")
  ResponseEntity<Map<String, Object>> get(@PathVariable String code) {
    return IdentityApiResponses.ok(permissions.get(code));
  }

  @GetMapping("/permissions/{code}/roles")
  ResponseEntity<Map<String, Object>> roles(
      @PathVariable String code,
      @RequestParam(name = "tenant_id", required = false) UUID tenant,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    return IdentityApiResponses.ok(permissions.references(code, tenant, page, size));
  }

  @PostMapping("/permissions")
  ResponseEntity<Map<String, Object>> create(@RequestBody Create command) {
    return IdentityApiResponses.ok(
        permissions.create(
            command.ownerService(),
            new PermissionCatalogService.Entry(
                command.code(),
                command.name(),
                PermissionCatalogService.PrincipalType.TENANT,
                command.groupCode(),
                command.groupName(),
                command.resourceCode(),
                command.resourceName(),
                command.actionCode(),
                command.description(),
                command.sort() == null ? 0 : command.sort(),
                command.supportedDataScopes(),
                command.oauthScope(),
                PermissionCatalogService.Status.ACTIVE),
            command.catalogVersion()));
  }

  @PutMapping("/permissions/{code}")
  ResponseEntity<Map<String, Object>> update(
      @PathVariable String code, @RequestBody Update command) {
    return IdentityApiResponses.ok(
        permissions.update(
            code,
            command.catalogVersion(),
            command.name(),
            command.groupCode(),
            command.groupName(),
            command.resourceName(),
            command.description(),
            command.sort(),
            command.status()));
  }
}
