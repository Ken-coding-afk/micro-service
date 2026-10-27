package com.ken.platform.foundation.data;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** 租户资源边界；记录必须从数据库读取，操作及数据范围必须由认证授权层验证。 */
public record TenantResources(String project, String environment, String service) {
  /** 不接收完整存储 key；数据库保存租户、资源类型、随机对象 ID 与创建者。 */
  public record ObjectRecord(UUID tenantId, String resource, UUID objectId, UUID ownerUserId) {}

  public TenantResources {
    segment(project);
    segment(service);
    if (!Set.of("local", "test", "prod").contains(environment == null ? "" : environment)) {
      throw invalid();
    }
  }

  public String cacheKey(String purpose, String businessKey) {
    UUID tenant = tenant();
    segment(purpose);
    if (businessKey == null
        || businessKey.isEmpty()
        || businessKey.length() > 512
        || businessKey
            .codePoints()
            .anyMatch(
                value -> value < 32 || value == 127 || (value >= 0xD800 && value <= 0xDFFF))) {
      throw invalid();
    }
    byte[] bytes = businessKey.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 512) {
      throw invalid();
    }
    return project
        + ":"
        + environment
        + ":"
        + service
        + ":"
        + tenant
        + ":"
        + purpose
        + ":"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String newObjectKey(String resource) {
    UUID tenant = tenant();
    segment(resource);
    return objectKey(tenant, resource, UUID.randomUUID());
  }

  public String authorizedObjectKey(ObjectRecord record, DataScope effectiveScope) {
    UUID tenant = tenant();
    if (record == null || !tenant.equals(record.tenantId())) {
      throw new PlatformException("OBJECT_NOT_FOUND", "对象不存在", 404);
    }
    if (effectiveScope == null
        || (effectiveScope == DataScope.SELF
            && (record.ownerUserId() == null
                || !record
                    .ownerUserId()
                    .toString()
                    .equals(CallContexts.current().principalId())))) {
      throw new PlatformException("OBJECT_ACCESS_DENIED", "无权访问该对象", 403);
    }
    segment(record.resource());
    if (record.objectId() == null) {
      throw invalid();
    }
    return objectKey(tenant, record.resource(), record.objectId());
  }

  public static int presignSeconds(int requested) {
    if (requested < 1 || requested > 300) {
      throw invalid();
    }
    return requested;
  }

  private String objectKey(UUID tenant, String resource, UUID object) {
    return project + "/" + environment + "/" + tenant + "/" + resource + "/" + object;
  }

  private static UUID tenant() {
    var context = CallContexts.current();
    if (context == null || context.tenantId() == null) {
      throw new PlatformException("TENANT_CONTEXT_REQUIRED", "需要已验证的租户上下文", 403);
    }
    return context.tenantId();
  }

  private static void segment(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) {
      throw invalid();
    }
  }

  private static PlatformException invalid() {
    return new PlatformException("RESOURCE_KEY_INVALID", "资源键或预签名期限不合法", 400);
  }
}
