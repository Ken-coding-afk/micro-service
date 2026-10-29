package com.ken.platform.identity.controller.management;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.login.TrustedClientAddress;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.login.PasswordResetService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({
  "/api/v1/identity",
  "/api/v1/identity/platform/tenants/{managementTenant}/identity"
})
public final class PasswordResetManagementController {
  public record Command(Long version) {}

  private final PasswordResetService resets;
  private final TrustedClientAddress source;

  public PasswordResetManagementController(
      PasswordResetService resets, TrustedClientAddress source) {
    this.resets = resets;
    this.source = source;
  }

  @PostMapping("/users/{id}/password-reset")
  ResponseEntity<Map<String, Object>> issue(
      @PathVariable UUID id,
      @RequestBody Command command,
      jakarta.servlet.http.HttpServletRequest request) {
    if (command.version() == null || command.version() < 0)
      throw new PlatformException("INVALID_ARGUMENT", "版本号不合法", 400);
    return IdentityApiResponses.ok(resets.issue(id, command.version(), source.resolve(request)));
  }
}
