package com.ken.platform.foundation.http;

import java.time.Instant;
import java.util.UUID;

/** 不可变调用上下文，可显式跨异步边界传递；身份须由认证适配器提供。 */
public record CallContext(
    String requestId,
    UUID tenantId,
    String principalId,
    String accessToken,
    String traceparent,
    Instant deadline) {

  public CallContext {
    if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,64}")) {
      requestId = UUID.randomUUID().toString();
    }
    if (traceparent != null
        && (!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")
            || traceparent.substring(3, 35).equals("0".repeat(32))
            || traceparent.substring(36, 52).equals("0".repeat(16)))) {
      traceparent = null;
    }
  }

  @Override
  public String toString() {
    return "CallContext[requestId="
        + requestId
        + ", tenantId="
        + tenantId
        + ", principalId="
        + principalId
        + ", deadline="
        + deadline
        + "]";
  }
}
