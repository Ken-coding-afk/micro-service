package com.ken.platform.identity.controller.management;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.login.SessionManagementService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public final class SessionManagementController {
  private final SessionManagementService sessions;

  public SessionManagementController(SessionManagementService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/sessions")
  ResponseEntity<Map<String, Object>> list(
      @RequestParam(name = "user_id", required = false) UUID user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam Map<String, String> query) {
    fields(query, Set.of("user_id", "page", "size"));
    return IdentityApiResponses.ok(sessions.list(user, page, size));
  }

  @PostMapping("/sessions/{id}:revoke")
  ResponseEntity<Map<String, Object>> revoke(
      @PathVariable UUID id,
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Map<String, Object> body) {
    fields(query, Set.of());
    fields(body, Set.of());
    sessions.revoke(id);
    return IdentityApiResponses.ok(Map.of("revoked", true));
  }

  @PostMapping("/users/{id}:sign-out-all")
  ResponseEntity<Map<String, Object>> signOutAll(
      @PathVariable UUID id,
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Map<String, Object> body) {
    fields(query, Set.of());
    fields(body, Set.of());
    return IdentityApiResponses.ok(Map.of("revoked", sessions.signOutAll(id)));
  }

  private static void fields(Map<String, ?> fields, Set<String> allowed) {
    if (fields != null && !allowed.containsAll(fields.keySet()))
      throw new PlatformException("INVALID_ARGUMENT", "请求包含不支持的字段", 400);
  }
}
