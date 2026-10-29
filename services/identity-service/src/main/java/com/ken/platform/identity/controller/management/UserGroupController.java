package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.group.UserGroupService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({
  "/api/v1/identity",
  "/api/v1/identity/platform/tenants/{managementTenant}/identity"
})
public final class UserGroupController {
  public interface Command {
    @JsonAnySetter
    default void unknown(String field, Object value) {
      throw new PlatformException("INVALID_ARGUMENT", "未知字段: " + field, 400);
    }
  }

  public record Create(
      String code,
      String name,
      String description,
      @JsonProperty("parent_id") UUID parentId,
      Integer sort,
      @JsonProperty("organization_version") Long organizationVersion)
      implements Command {}

  public record Update(
      Long version,
      @JsonProperty("organization_version") Long organizationVersion,
      String name,
      String description,
      int sort,
      String status)
      implements Command {}

  public record Move(
      Long version,
      @JsonProperty("organization_version") Long organizationVersion,
      @JsonProperty("parent_id") UUID parentId,
      int sort)
      implements Command {}

  public record Delete(Long version, @JsonProperty("organization_version") Long organizationVersion)
      implements Command {}

  public record Members(
      @JsonProperty("group_version") Long groupVersion, List<UserGroupService.Reference> users)
      implements Command {}

  public record Roles(
      @JsonProperty("group_version") Long groupVersion, List<UserGroupService.Reference> roles)
      implements Command {}

  public record Groups(
      @JsonProperty("user_version") Long userVersion, List<UserGroupService.Reference> groups)
      implements Command {}

  private final UserGroupService groups;

  public UserGroupController(UserGroupService groups) {
    this.groups = groups;
  }

  @GetMapping("/groups")
  ResponseEntity<Map<String, Object>> list(
      @RequestParam(name = "parent_id", required = false) UUID parent,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return IdentityApiResponses.ok(groups.list(parent, q, page, size));
  }

  @GetMapping("/groups/{id}")
  ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
    return IdentityApiResponses.ok(groups.get(id));
  }

  @PostMapping("/groups")
  ResponseEntity<Map<String, Object>> create(@RequestBody Create command) {
    return IdentityApiResponses.ok(
        groups.create(
            command.code(),
            command.name(),
            command.description(),
            command.parentId(),
            command.sort() == null ? 0 : command.sort(),
            command.organizationVersion()));
  }

  @PutMapping("/groups/{id}")
  ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody Update command) {
    return IdentityApiResponses.ok(
        groups.update(
            id,
            command.version(),
            command.organizationVersion(),
            command.name(),
            command.description(),
            command.sort(),
            command.status()));
  }

  @PostMapping("/groups/{id}:move")
  ResponseEntity<Map<String, Object>> move(@PathVariable UUID id, @RequestBody Move command) {
    return IdentityApiResponses.ok(
        groups.move(
            id,
            command.version(),
            command.organizationVersion(),
            command.parentId(),
            command.sort()));
  }

  @DeleteMapping("/groups/{id}")
  ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id, @RequestBody Delete command) {
    return IdentityApiResponses.ok(
        groups.delete(id, command.version(), command.organizationVersion()));
  }

  @GetMapping("/groups/{id}/members")
  ResponseEntity<Map<String, Object>> members(
      @PathVariable UUID id,
      @RequestParam(name = "include_descendants", defaultValue = "false") boolean descendants,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return IdentityApiResponses.ok(groups.members(id, descendants, q, page, size));
  }

  @PostMapping("/groups/{id}/members:add")
  ResponseEntity<Map<String, Object>> add(@PathVariable UUID id, @RequestBody Members command) {
    return IdentityApiResponses.ok(
        groups.changeMembers(id, command.groupVersion(), command.users(), true));
  }

  @PostMapping("/groups/{id}/members:remove")
  ResponseEntity<Map<String, Object>> remove(@PathVariable UUID id, @RequestBody Members command) {
    return IdentityApiResponses.ok(
        groups.changeMembers(id, command.groupVersion(), command.users(), false));
  }

  @GetMapping("/groups/{id}/roles")
  ResponseEntity<Map<String, Object>> roles(@PathVariable UUID id) {
    return IdentityApiResponses.ok(groups.roles(id));
  }

  @PutMapping("/groups/{id}/roles")
  ResponseEntity<Map<String, Object>> roles(@PathVariable UUID id, @RequestBody Roles command) {
    return IdentityApiResponses.ok(
        groups.replaceRoles(id, command.groupVersion(), command.roles()));
  }

  @GetMapping("/users/{id}/groups")
  ResponseEntity<Map<String, Object>> userGroups(@PathVariable UUID id) {
    return IdentityApiResponses.ok(groups.userGroups(id));
  }

  @PutMapping("/users/{id}/groups")
  ResponseEntity<Map<String, Object>> userGroups(
      @PathVariable UUID id, @RequestBody Groups command) {
    return IdentityApiResponses.ok(
        groups.replaceUserGroups(id, command.userVersion(), command.groups()));
  }

  @GetMapping("/roles/{id}/groups")
  ResponseEntity<Map<String, Object>> roleGroups(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return IdentityApiResponses.ok(groups.roleGroups(id, page, size));
  }
}
