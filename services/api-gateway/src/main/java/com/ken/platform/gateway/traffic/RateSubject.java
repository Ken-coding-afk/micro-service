package com.ken.platform.gateway.traffic;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.CurrentPrincipal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 仅由验证后的主体创建，不读取租户请求头。 */
public record RateSubject(String type, String tenant, String subject, String clientId) {
  public static final String ATTRIBUTE = RateSubject.class.getName();

  public static RateSubject from(CurrentPrincipal principal) {
    return new RateSubject(
        principal.principalType(),
        principal.tenantId() == null ? null : principal.tenantId().toString(),
        principal.subject(),
        principal.clientId());
  }

  public String key(String project, String environment, String route) {
    String identity =
        switch (type) {
          case "user" -> tenant;
          case "service" -> clientId;
          case "platform" -> subject;
          default -> null;
        };
    if (identity == null || identity.isBlank())
      throw new PlatformException("INVALID_CREDENTIALS", "缺少可信限流主体", 401);
    try {
      byte[] value =
          JsonMapper.builder()
              .build()
              .writeValueAsBytes(List.of(project, environment, type, identity, route));
      return "platform-"
          + type
          + "-"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
