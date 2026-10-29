package com.ken.platform.identity.controller.management;

import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.authorization.TenantManagementQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({
  "/api/v1/identity",
  "/api/v1/identity/platform/tenants/{managementTenant}/identity"
})
public final class TenantManagementQueryController {
  private final TenantManagementQueryService queries;

  public TenantManagementQueryController(TenantManagementQueryService queries) {
    this.queries = queries;
  }

  @GetMapping("/users")
  ResponseEntity<Map<String, Object>> users(
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(name = "group_id", required = false) UUID group,
      @RequestParam(name = "include_descendants", defaultValue = "false") boolean descendants) {
    return IdentityApiResponses.ok(queries.users(page, size, q, status, group, descendants));
  }

  @GetMapping("/users/{id}")
  ResponseEntity<Map<String, Object>> user(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.user(id));
  }

  @GetMapping("/users/{id}/roles")
  ResponseEntity<Map<String, Object>> userRoles(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.userRoles(id));
  }

  @GetMapping("/roles")
  ResponseEntity<Map<String, Object>> roles(
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(name = "assignable_to", required = false) UUID assignableTo) {
    return IdentityApiResponses.ok(queries.roles(page, size, assignableTo, q));
  }

  @GetMapping("/role-permissions/{id}")
  ResponseEntity<Map<String, Object>> rolePermissions(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.rolePermissions(id));
  }

  @GetMapping("/roles/{id}/members")
  ResponseEntity<Map<String, Object>> roleMembers(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    return IdentityApiResponses.ok(queries.roleMembers(id, page, size));
  }

  @GetMapping("/permissions")
  ResponseEntity<Map<String, Object>> permissions(
      @RequestParam(defaultValue = "false") boolean assignable,
      @RequestParam(name = "role_id", required = false) UUID roleId) {
    return IdentityApiResponses.ok(queries.permissions(assignable, roleId));
  }

  @GetMapping("/users/{id}/effective-permissions")
  ResponseEntity<Map<String, Object>> effectivePermissions(@PathVariable UUID id) {
    return IdentityApiResponses.ok(queries.effectivePermissions(id));
  }
}
