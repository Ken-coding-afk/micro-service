package com.ken.platform.identity.service.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 查询端再次遮蔽已知敏感字段并限定详情体积；不改写已持久化的审计事实。 */
@Service
public final class AuditDetailSanitizer {
  public record Result(Map<String, Object> details, boolean truncated) {
    public Result {
      details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
  }

  private static final Set<String> SENSITIVE =
      Set.of(
          "password",
          "passwordhash",
          "initialpassword",
          "temporarypassword",
          "secret",
          "clientsecret",
          "accesstoken",
          "refreshtoken",
          "idtoken",
          "token",
          "tokenhash",
          "tokenvalue",
          "authorizationcode",
          "oauthcode",
          "state",
          "cookie",
          "authorization",
          "credential",
          "resettoken",
          "requestbody",
          "responsebody",
          "rawrequest",
          "email",
          "verifiedemail",
          "phone",
          "mobile");
  private final JsonMapper json = JsonMapper.builder().build();

  private static final class Budget {
    int nodes = 1000;
    boolean truncated;
  }

  public Result sanitize(String source) {
    if (source == null || source.length() > 16384)
      return new Result(Map.of("details_truncated", true), true);
    Object parsed;
    try {
      parsed = json.readValue(source, Object.class);
    } catch (tools.jackson.core.JacksonException unsupported) {
      return new Result(Map.of("unsupported_detail_shape", true), true);
    }
    if (!(parsed instanceof Map<?, ?>))
      return new Result(Map.of("unsupported_detail_shape", true), true);
    var budget = new Budget();
    var safe = object((Map<?, ?>) parsed, 0, budget);
    return new Result(safe, budget.truncated);
  }

  private static Map<String, Object> object(Map<?, ?> values, int depth, Budget budget) {
    var result = new LinkedHashMap<String, Object>();
    for (var entry : values.entrySet()) {
      if (result.size() >= 100 || budget.nodes-- <= 0) {
        budget.truncated = true;
        break;
      }
      if (!(entry.getKey() instanceof String key) || key.length() > 128) {
        budget.truncated = true;
        continue;
      }
      String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
      boolean sensitive =
          SENSITIVE.contains(normalized)
              || normalized.contains("secret")
              || key.contains("密码")
              || key.contains("密钥")
              || key.contains("凭据")
              || key.contains("令牌")
              || key.contains("邮箱")
              || key.contains("手机号");
      result.put(key, sensitive ? "[REDACTED]" : value(entry.getValue(), depth + 1, budget));
    }
    return Collections.unmodifiableMap(result);
  }

  private static Object value(Object value, int depth, Budget budget) {
    if (depth > 8 || budget.nodes-- <= 0) {
      budget.truncated = true;
      return "[TRUNCATED]";
    }
    if (value instanceof Map<?, ?> map) return object(map, depth, budget);
    if (value instanceof List<?> list) {
      var result = new ArrayList<Object>();
      for (Object item : list) {
        if (result.size() >= 200 || budget.nodes <= 0) {
          budget.truncated = true;
          break;
        }
        result.add(value(item, depth + 1, budget));
      }
      return Collections.unmodifiableList(result);
    }
    if (value instanceof String text && text.codePointCount(0, text.length()) > 1024) {
      budget.truncated = true;
      return text.substring(0, text.offsetByCodePoints(0, 1024)) + "…";
    }
    return value;
  }
}
