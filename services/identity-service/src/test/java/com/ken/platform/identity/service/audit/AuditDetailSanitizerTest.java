package com.ken.platform.identity.service.audit;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditDetailSanitizerTest {
  private final AuditDetailSanitizer sanitizer = new AuditDetailSanitizer();
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void masksKnownSecretsAndContactFieldsRecursivelyButKeepsSafeDifferenceAndNull() {
    var result =
        sanitizer.sanitize(
            "{\"before\":null,\"after\":{\"display_name\":\"新名称\",\"password\":\"SECRET_ONE\",\"EMAIL\":\"private@example.invalid\"},\"nested\":[{\"accessToken\":\"SECRET_TWO\",\"密码\":\"SECRET_THREE\"}],\"credential_issued\":true}");
    String safe = json.writeValueAsString(result.details());
    assertThat(safe)
        .contains("新名称", "[REDACTED]", "credential_issued")
        .doesNotContain("SECRET_ONE", "SECRET_TWO", "SECRET_THREE", "private@example.invalid");
    assertThat(result.details())
        .containsEntry("before", null)
        .containsEntry("credential_issued", true);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void boundsLargeDetailsBeforeParsingAndRejectsUnsupportedRootShape() {
    assertThat(
            sanitizer
                .sanitize(json.writeValueAsString(Map.of("large", "x".repeat(20000))))
                .truncated())
        .isTrue();
    var scalar = sanitizer.sanitize("[\"DO_NOT_RETURN_RAW_ROOT\"]");
    assertThat(scalar.details()).containsEntry("unsupported_detail_shape", true);
    assertThat(json.writeValueAsString(scalar.details())).doesNotContain("DO_NOT_RETURN_RAW_ROOT");
    assertThat(sanitizer.sanitize("{}").truncated()).isFalse();
  }

  @Test
  void decoderConstraintsProduceExplicitProjectionMarkerWithoutReturningRawPayload() {
    String deep = "{\"child\":".repeat(1100) + "\"PRIVATE_TOO_DEEP\"" + "}".repeat(1100);
    var result = sanitizer.sanitize(deep);
    assertThat(result.truncated()).isTrue();
    assertThat(json.writeValueAsString(result.details())).doesNotContain("PRIVATE_TOO_DEEP");
  }

  @Test
  void truncatesUnicodeAtCodePointBoundaryAndBoundsCollectionDepthAndNodeCount() {
    String text = "😀".repeat(1100);
    var result = sanitizer.sanitize(json.writeValueAsString(Map.of("note", text)));
    assertThat(result.truncated()).isTrue();
    String safe = (String) result.details().get("note");
    assertThat(safe.codePointCount(0, safe.length())).isEqualTo(1025);
    Object nested = Map.of("secret", "HIDDEN");
    for (int depth = 0; depth < 20; depth++) nested = Map.of("child", nested);
    assertThat(sanitizer.sanitize(json.writeValueAsString(nested)).truncated()).isTrue();
    var items =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> Map.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6))
            .toList();
    assertThat(sanitizer.sanitize(json.writeValueAsString(Map.of("items", items))).truncated())
        .isTrue();
    var many =
        sanitizer.sanitize(
            json.writeValueAsString(Map.of("items", java.util.Collections.nCopies(300, 1))));
    assertThat(((List<?>) many.details().get("items")).size()).isEqualTo(200);
    assertThat(many.truncated()).isTrue();
  }
}
