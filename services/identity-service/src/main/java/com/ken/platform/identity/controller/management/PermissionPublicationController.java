package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.oauth.PublisherTokens;
import com.ken.platform.identity.service.permission.PermissionCatalogService;
import com.ken.platform.identity.service.permission.PermissionManifest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class PermissionPublicationController {
  public record Command(
      String service,
      @JsonProperty("release_version") String releaseVersion,
      String checksum,
      String content,
      @JsonProperty("catalog_version") long catalogVersion) {
    @JsonAnySetter
    public void unknown(String name, Object value) {
      throw new PlatformException("INVALID_ARGUMENT", "未知字段: " + name, 400);
    }
  }

  private final PermissionCatalogService catalogs;
  private final PublisherTokens tokens;
  private final JdbcTemplate jdbc;

  public PermissionPublicationController(
      PermissionCatalogService catalogs, PublisherTokens tokens, JdbcTemplate jdbc) {
    this.catalogs = catalogs;
    this.tokens = tokens;
    this.jdbc = jdbc;
  }

  @GetMapping("/internal/v1/permission-catalogs/version")
  ResponseEntity<Map<String, Object>> version() {
    return IdentityApiResponses.ok(
        Map.of(
            "catalog_version",
            jdbc.queryForObject(
                "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class)));
  }

  @PostMapping("/internal/v1/permission-catalogs:sync")
  ResponseEntity<Map<String, Object>> publish(@RequestBody Command command) {
    var manifest =
        PermissionManifest.parse(
            command.service(), command.releaseVersion(), command.checksum(), command.content());
    return IdentityApiResponses.ok(
        catalogs.publish(
            manifest,
            command.catalogVersion(),
            () -> tokens.require(CallContexts.current().accessToken(), command.service())));
  }
}
