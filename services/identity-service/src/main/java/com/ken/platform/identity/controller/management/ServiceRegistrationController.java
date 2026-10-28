package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.oauth.ClientManagementService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity/platform/service-audiences")
public class ServiceRegistrationController {
  public record Command(String service, String audience) {
    @JsonAnySetter
    public void unknown(String name, Object value) {
      throw new PlatformException("INVALID_ARGUMENT", "未知字段: " + name, 400);
    }
  }

  private final ClientManagementService clients;

  public ServiceRegistrationController(ClientManagementService clients) {
    this.clients = clients;
  }

  @PostMapping
  ResponseEntity<Map<String, Object>> register(@RequestBody Command command) {
    return IdentityApiResponses.ok(clients.registerService(command.service(), command.audience()));
  }
}
