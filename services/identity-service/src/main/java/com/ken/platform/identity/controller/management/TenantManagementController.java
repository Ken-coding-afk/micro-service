package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.role.RoleAssignmentService;
import com.ken.platform.identity.service.role.RoleManagementService;
import com.ken.platform.identity.service.role.RolePermissionService;
import com.ken.platform.identity.service.user.UserProfileService;
import com.ken.platform.identity.service.user.UserStatusService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 仅适配显式字段；tenant/actor 来自本地实时认证，所有写入重查和审计由既有业务入口负责。 */
@RestController
@RequestMapping("/api/v1/identity")
public final class TenantManagementController {
  public record ProfileCommand(Long version, @JsonProperty("display_name") String displayName) {
    @JsonAnySetter
    public void rejectUnknownField(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的资料字段");
    }
  }

  public record StatusCommand(Long version, UserStatusService.Status status) {}

  public record AssignmentCommand(
      @JsonProperty("user_version") Long userVersion,
      List<RoleAssignmentService.RoleReference> roles) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
      throw new IllegalArgumentException("不支持的角色分配字段");
    }
  }

  public record PermissionCommand(
      @JsonProperty("role_version") Long roleVersion,
      @JsonProperty("catalog_version") Long catalogVersion,
      List<RolePermissionService.Grant> grants) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
      throw new IllegalArgumentException("不支持的角色授权字段");
    }
  }

  public record CreateRoleCommand(String code, String name, String description) {}

  public record RoleVersionCommand(Long version) {}

  public record UpdateRoleCommand(
      Long version, String name, RoleManagementService.Status status, String description) {}

  public record CopyRoleCommand(
      @JsonProperty("role_version") Long roleVersion,
      @JsonProperty("catalog_version") Long catalogVersion,
      String code,
      String name,
      String description) {}

  private final UserStatusService users;
  private final RoleAssignmentService assignments;
  private final RolePermissionService permissions;
  private final RoleManagementService roles;
  private final UserProfileService profiles;

  public TenantManagementController(
      UserStatusService users,
      RoleAssignmentService assignments,
      RolePermissionService permissions,
      RoleManagementService roles,
      UserProfileService profiles) {
    this.users = users;
    this.assignments = assignments;
    this.permissions = permissions;
    this.roles = roles;
    this.profiles = profiles;
  }

  @PutMapping("/users/{id}")
  ResponseEntity<Map<String, Object>> status(
      @PathVariable UUID id, @RequestBody StatusCommand command) {
    return IdentityApiResponses.ok(
        users.changeStatus(id, command.status(), version(command.version())));
  }

  @PutMapping("/users/{id}/profile")
  ResponseEntity<Map<String, Object>> profile(
      @PathVariable UUID id, @RequestBody ProfileCommand command) {
    return IdentityApiResponses.ok(
        profiles.update(id, version(command.version()), command.displayName()));
  }

  @PutMapping("/users/{id}/roles")
  ResponseEntity<Map<String, Object>> assign(
      @PathVariable UUID id, @RequestBody AssignmentCommand command) {
    return IdentityApiResponses.ok(
        assignments.replace(id, version(command.userVersion()), command.roles()));
  }

  @PutMapping("/role-permissions/{id}")
  ResponseEntity<Map<String, Object>> permissions(
      @PathVariable UUID id, @RequestBody PermissionCommand command) {
    return IdentityApiResponses.ok(
        permissions.replace(
            id,
            version(command.roleVersion()),
            version(command.catalogVersion()),
            command.grants()));
  }

  @PostMapping("/roles")
  ResponseEntity<Map<String, Object>> create(@RequestBody CreateRoleCommand command) {
    return IdentityApiResponses.ok(
        roles.create(command.code(), command.name(), command.description()));
  }

  @PutMapping("/roles/{id}")
  ResponseEntity<Map<String, Object>> update(
      @PathVariable UUID id, @RequestBody UpdateRoleCommand command) {
    return IdentityApiResponses.ok(
        roles.update(
            id,
            version(command.version()),
            command.name(),
            command.status(),
            command.description()));
  }

  @PostMapping("/roles/{id}:copy")
  ResponseEntity<Map<String, Object>> copy(
      @PathVariable UUID id, @RequestBody CopyRoleCommand command) {
    return IdentityApiResponses.ok(
        roles.copy(
            id,
            version(command.roleVersion()),
            version(command.catalogVersion()),
            command.code(),
            command.name(),
            command.description()));
  }

  @DeleteMapping("/roles/{id}")
  ResponseEntity<Map<String, Object>> delete(
      @PathVariable UUID id, @RequestBody RoleVersionCommand command) {
    return IdentityApiResponses.ok(roles.delete(id, version(command.version())));
  }

  private static long version(Long value) {
    if (value == null || value < 0) throw invalid();
    return value;
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_ARGUMENT", "请求字段、类型或版本不合法", 400);
  }
}
