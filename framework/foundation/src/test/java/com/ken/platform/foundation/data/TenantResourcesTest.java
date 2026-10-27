package com.ken.platform.foundation.data;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TenantResourcesTest {
  private final UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID actor = UUID.randomUUID();
  private final TenantResources resources =
      new TenantResources("doc-insight", "local", "document-service");

  private CallContexts.Scope context(UUID tenant) {
    return CallContexts.bind(
        new CallContext("storage-test", tenant, actor.toString(), null, null, null));
  }

  private static void failure(
      String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            PlatformException.class,
            error -> {
              assertThat(error.code()).isEqualTo(code);
              assertThat(error.status()).isEqualTo(status);
            });
  }

  @Test
  void sharedVectorsMatchPythonAndCannotCollideAcrossBoundaries() throws Exception {
    try (var ignored = context(tenant);
        var input = getClass().getResourceAsStream("/tenant-resource-vectors.json")) {
      for (var vector : JsonMapper.builder().build().readTree(input)) {
        assertThat(resources.cacheKey("document", vector.get("business_key").asString()))
            .isEqualTo(vector.get("cache_key").asString());
      }
      assertThat(resources.cacheKey("document", "a:b"))
          .isNotEqualTo(resources.cacheKey("document", "a/b"));
      assertThat(
              new TenantResources("doc-insight", "test", "document-service")
                  .cacheKey("document", "a:b"))
          .isNotEqualTo(resources.cacheKey("document", "a:b"));
      assertThat(
              new TenantResources("other-project", "local", "document-service")
                  .cacheKey("document", "a:b"))
          .isNotEqualTo(resources.cacheKey("document", "a:b"));
      assertThat(
              new TenantResources("doc-insight", "local", "analysis-service")
                  .cacheKey("document", "a:b"))
          .isNotEqualTo(resources.cacheKey("document", "a:b"));
    }
    String first;
    try (var ignored = context(tenant)) {
      first = resources.cacheKey("document", "42");
    }
    try (var ignored = context(UUID.randomUUID())) {
      assertThat(resources.cacheKey("document", "42")).isNotEqualTo(first);
    }
  }

  @Test
  void absentTenantAndUnsafeSegmentsFailBeforeStorage() {
    failure("TENANT_CONTEXT_REQUIRED", 403, () -> resources.cacheKey("document", "42"));
    failure("TENANT_CONTEXT_REQUIRED", 403, () -> resources.newObjectKey("document"));
    try (var ignored = context(null)) {
      failure("TENANT_CONTEXT_REQUIRED", 403, () -> resources.newObjectKey("document"));
    }
    try (var ignored = context(tenant)) {
      for (String unsafe : List.of("../other", "a/b", "a:b", "%2f", "", "{hash}")) {
        failure("RESOURCE_KEY_INVALID", 400, () -> resources.newObjectKey(unsafe));
        failure("RESOURCE_KEY_INVALID", 400, () -> resources.cacheKey(unsafe, "42"));
      }
      for (String unsafe : List.of("", "a\n", "中".repeat(200), "x".repeat(513), "\uD800")) {
        failure("RESOURCE_KEY_INVALID", 400, () -> resources.cacheKey("document", unsafe));
      }
    }
  }

  @Test
  void objectScopeRequiresDatabaseOwnershipAndNeverCrossesTenant() {
    UUID object = UUID.randomUUID();
    var own = new TenantResources.ObjectRecord(tenant, "document", object, actor);
    try (var ignored = context(tenant)) {
      String expected = "doc-insight/local/" + tenant + "/document/" + object;
      assertThat(resources.authorizedObjectKey(own, DataScope.SELF)).isEqualTo(expected);
      assertThat(resources.authorizedObjectKey(own, DataScope.TENANT)).isEqualTo(expected);
      failure("OBJECT_ACCESS_DENIED", 403, () -> resources.authorizedObjectKey(own, null));
      var another = new TenantResources.ObjectRecord(tenant, "document", object, UUID.randomUUID());
      failure(
          "OBJECT_ACCESS_DENIED",
          403,
          () -> resources.authorizedObjectKey(another, DataScope.SELF));
      assertThat(resources.authorizedObjectKey(another, DataScope.TENANT)).isEqualTo(expected);
      var foreign = new TenantResources.ObjectRecord(UUID.randomUUID(), "document", object, actor);
      failure(
          "OBJECT_NOT_FOUND", 404, () -> resources.authorizedObjectKey(foreign, DataScope.TENANT));
      failure("OBJECT_NOT_FOUND", 404, () -> resources.authorizedObjectKey(null, DataScope.TENANT));
      var noOwner = new TenantResources.ObjectRecord(tenant, "document", object, null);
      failure(
          "OBJECT_ACCESS_DENIED",
          403,
          () -> resources.authorizedObjectKey(noOwner, DataScope.SELF));
    }
  }

  @Test
  void generatedObjectsAreRandomAndPresignLimitIsFiveMinutes() {
    try (var ignored = context(tenant)) {
      String first = resources.newObjectKey("document");
      String second = resources.newObjectKey("document");
      assertThat(first)
          .startsWith("doc-insight/local/" + tenant + "/document/")
          .isNotEqualTo(second);
      assertThat(UUID.fromString(first.substring(first.lastIndexOf('/') + 1)).version())
          .isEqualTo(4);
    }
    assertThat(TenantResources.presignSeconds(300)).isEqualTo(300);
    assertThat(TenantResources.presignSeconds(1)).isEqualTo(1);
    for (int invalid : List.of(0, -1, 301, Integer.MAX_VALUE)) {
      failure("RESOURCE_KEY_INVALID", 400, () -> TenantResources.presignSeconds(invalid));
    }
  }
}
