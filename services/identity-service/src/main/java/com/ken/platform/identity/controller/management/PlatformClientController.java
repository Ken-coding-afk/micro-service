package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.service.oauth.ClientManagementService;
import com.ken.platform.identity.service.oauth.ClientPolicyRules;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 平台控制面；一次性凭据不进入 GET、审计或响应对象的 toString。 */
@RestController
@RequestMapping("/api/v1/identity/platform/oauth-clients")
public final class PlatformClientController {
  public record Command(
      @JsonProperty("client_id") String clientId,
      String name,
      @JsonProperty("principal_type") String principalType,
      @JsonProperty("owner_tenant_id") UUID ownerTenantId,
      @JsonProperty("allowed_tenant_ids") Set<UUID> allowedTenantIds,
      Set<String> audiences,
      Set<String> scopes,
      @JsonProperty("redirect_uris") Set<String> redirectUris,
      @JsonProperty("logout_uris") Set<String> logoutUris,
      @JsonProperty("public_client") Boolean publicClient,
      @JsonProperty("first_party") Boolean firstParty,
      ClientPolicyDO.Status status,
      Long version) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的客户端字段");
    }
  }

  public record Client(
      UUID id,
      @JsonProperty("client_id") String clientId,
      String name,
      @JsonProperty("principal_type") String principalType,
      @JsonProperty("owner_tenant_id") UUID ownerTenantId,
      @JsonProperty("allowed_tenant_ids") Set<UUID> allowedTenantIds,
      Set<String> audiences,
      Set<String> scopes,
      @JsonProperty("redirect_uris") Set<String> redirectUris,
      @JsonProperty("logout_uris") Set<String> logoutUris,
      @JsonProperty("public_client") boolean publicClient,
      @JsonProperty("first_party") boolean firstParty,
      ClientPolicyDO.Status status,
      long version) {}

  public record Page(
      int page, int size, @JsonProperty("has_more") boolean hasMore, List<Client> items) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record Created(
      Client client, @JsonProperty("client_secret") String clientSecret, boolean replayed) {
    @Override
    public String toString() {
      return "CreatedClient[id=" + client.id() + ", credential=REDACTED]";
    }
  }

  private final ClientManagementService clients;

  public PlatformClientController(ClientManagementService clients) {
    this.clients = clients;
  }

  @GetMapping
  ResponseEntity<Map<String, Object>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q) {
    var result = clients.list(page, size, q);
    return IdentityApiResponses.ok(
        new Page(
            result.page(),
            result.size(),
            result.hasMore(),
            result.items().stream().map(PlatformClientController::view).toList()));
  }

  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
    return IdentityApiResponses.ok(view(clients.get(id)));
  }

  @PostMapping
  ResponseEntity<Map<String, Object>> create(@RequestBody Command command) {
    if (command.version() != null) throw invalid();
    var result = clients.create(definition(command));
    String secret = null;
    if (result.credential() != null)
      try (var credential = result.credential()) {
        secret = credential.revealOnce();
      }
    return IdentityApiResponses.ok(new Created(view(result.client()), secret, result.replayed()));
  }

  @PutMapping("/{id}")
  ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody Command command) {
    if (command.version() == null || command.version() < 0) throw invalid();
    return IdentityApiResponses.ok(
        view(clients.update(id, command.version(), definition(command))));
  }

  public record Rotation(Long version) {
    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
      throw new IllegalArgumentException("不支持的凭据轮换字段");
    }
  }

  @PostMapping("/{id}/secret:rotate")
  ResponseEntity<Map<String, Object>> rotate(@PathVariable UUID id, @RequestBody Rotation command) {
    if (command.version() == null || command.version() < 0) throw invalid();
    var result = clients.rotate(id, command.version());
    try (var credential = result.credential()) {
      return IdentityApiResponses.ok(
          new Created(view(result.client()), credential.revealOnce(), false));
    }
  }

  private static ClientPolicyRules.Definition definition(Command command) {
    if (command.publicClient() == null
        || command.firstParty() == null
        || command.principalType() == null) throw invalid();
    ClientPolicyDO.PrincipalType type;
    try {
      type = ClientPolicyDO.PrincipalType.valueOf(command.principalType().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    return new ClientPolicyRules.Definition(
        command.clientId(),
        command.name(),
        type,
        command.ownerTenantId(),
        command.allowedTenantIds(),
        command.audiences(),
        command.scopes(),
        command.redirectUris(),
        command.logoutUris(),
        command.publicClient(),
        command.firstParty(),
        command.status());
  }

  private static Client view(ClientManagementService.View value) {
    var d = value.definition();
    return new Client(
        value.id(),
        d.clientId(),
        d.name(),
        d.principalType().name().toLowerCase(Locale.ROOT),
        d.ownerTenantId(),
        d.allowedTenantIds(),
        d.audiences(),
        d.scopes(),
        d.redirectUris(),
        d.logoutUris(),
        d.publicClient(),
        d.firstParty(),
        d.status(),
        value.version());
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_ARGUMENT", "客户端字段、类型或版本不合法", 400);
  }
}
