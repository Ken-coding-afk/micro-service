package com.ken.platform.identity.controller.management;

import com.ken.platform.identity.service.audit.AuditQueryService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public final class AuditManagementController {
  private final AuditQueryService audits;

  public AuditManagementController(AuditQueryService audits) {
    this.audits = audits;
  }

  @GetMapping({"/audits", "/platform/audits"})
  ResponseEntity<Map<String, Object>> list(
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(name = "actor_id", required = false) UUID actor,
      @RequestParam(required = false) String action,
      @RequestParam(name = "target_id", required = false) String target,
      @RequestParam(required = false) String result) {
    boolean platform =
        request
            .getRequestURI()
            .equals(request.getContextPath() + "/api/v1/identity/platform/audits");
    return IdentityApiResponses.ok(
        audits.query(platform, page, size, from, to, actor, action, target, result));
  }
}
