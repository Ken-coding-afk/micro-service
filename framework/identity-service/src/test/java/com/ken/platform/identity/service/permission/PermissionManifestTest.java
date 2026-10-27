package com.ken.platform.identity.service.permission;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionManifestTest {
  private static final String YAML =
      """
      service: document-service
      permissions:
        - code: document:read
          resource_code: document
          resource_name: 文档
          action_code: read
          name: 查看文档
          data_scopes: [SELF, TENANT]
          oauth_scope: documents.read
      """;

  private static String digest(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private PermissionManifest parse(String text) throws Exception {
    return PermissionManifest.parse("document-service", "2.0.0", digest(text), text);
  }

  @Test
  void acceptsDocumentedYamlAndFreezesEntries() throws Exception {
    var result = parse(YAML);
    assertThat(result.service()).isEqualTo("document-service");
    assertThat(result.releaseVersion()).isEqualTo("2.0.0");
    assertThat(result.checksum()).isEqualTo(digest(YAML));
    assertThat(result.entries()).hasSize(1);
    var permission = result.entries().getFirst();
    assertThat(permission.dataScopes()).containsExactlyInAnyOrder(DataScope.SELF, DataScope.TENANT);
    assertThat(permission.groupCode()).isNull();
    assertThat(permission.status()).isEqualTo(PermissionCatalogService.Status.ACTIVE);
    assertThatThrownBy(() -> result.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> permission.dataScopes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rawChecksumAndOwnerMustMatch() throws Exception {
    String checksum = digest(YAML);
    assertThatThrownBy(
            () -> PermissionManifest.parse("document-service", "2", checksum, YAML + "\n"))
        .isInstanceOf(PlatformException.class);
    assertThatThrownBy(() -> PermissionManifest.parse("another-service", "2", checksum, YAML))
        .isInstanceOf(PlatformException.class);
    assertThatThrownBy(
            () -> PermissionManifest.parse("document-service", "2", "0".repeat(64), YAML))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void rejectsUnknownDuplicateUnsafeAndMistypedInput() throws Exception {
    for (String invalid :
        List.of(
            YAML + "unexpected: true\n",
            YAML + "service: another-service\n",
            YAML.replace("[SELF, TENANT]", "[SELF, SELF]"),
            YAML.replace("[SELF, TENANT]", "[GLOBAL]"),
            YAML.replace("[SELF, TENANT]", "null"),
            YAML.replace("name: 查看文档", "name: 42"),
            YAML.replace("name: 查看文档", "name: 查看文档\n      sort: true"),
            "!!java.net.URL ['https://example.test']",
            "service: &owner [document-service]\npermissions: *owner\n")) {
      assertThatThrownBy(() -> parse(invalid)).as(invalid).isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void enforcesByteAndEntryLimitsAndExplicitEmptyObjectScope() throws Exception {
    String huge = "#中".repeat(400_000);
    assertThatThrownBy(() -> parse(huge)).isInstanceOf(PlatformException.class);
    String many = "service: document-service\npermissions:\n" + "  - {}\n".repeat(1001);
    assertThatThrownBy(() -> parse(many)).isInstanceOf(PlatformException.class);
    assertThat(parse(YAML.replace("[SELF, TENANT]", "[]")).entries().getFirst().dataScopes())
        .isEmpty();
  }
}
