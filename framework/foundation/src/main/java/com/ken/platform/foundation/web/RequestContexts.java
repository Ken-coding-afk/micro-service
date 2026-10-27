package com.ken.platform.foundation.web;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;

final class RequestContexts {
  static final String ATTRIBUTE = CallContext.class.getName();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RequestContexts() {}

  static CallContext incoming(
      String requestId, String trace, String rawDeadline, Clock clock, double budget) {
    Instant deadline = clock.instant().plusMillis((long) (budget * 1000));
    if (rawDeadline != null) {
      try {
        Instant upstream = Instant.ofEpochMilli(Long.parseLong(rawDeadline));
        if (upstream.isBefore(deadline)) {
          deadline = upstream;
        }
      } catch (NumberFormatException ignored) {
        /* 不信任的格式不能扩大本地预算。 */
      }
    }
    // 外部 tenant/user/token 头不构成可信身份；T-05B 的认证适配器随后绑定 principal。
    return new CallContext(requestId, null, null, null, trace, deadline);
  }

  static byte[] error(PlatformException error, CallContext context) {
    var body = new LinkedHashMap<String, Object>();
    body.put("code", error.code());
    body.put("message", error.getMessage());
    body.put("data", null);
    body.put("request_id", context.requestId());
    return JSON.writeValueAsBytes(body);
  }
}
