package com.ken.platform.foundation.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 与 Python 共用的 JSON envelope；不包含用户凭据或业务执行状态。 */
public record Event(
    @JsonProperty("event_id") UUID eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("tenant_id") UUID tenantId,
    String producer,
    @JsonProperty("occurred_at")
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant occurredAt,
    String traceparent,
    @JsonProperty("aggregate_id") String aggregateId,
    @JsonProperty("aggregate_version") Long aggregateVersion,
    Map<String, Object> data) {
  public static final int MAX_BYTES = 1024 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private static final Set<String> FORBIDDEN =
      Set.of("password", "access_token", "refresh_token", "client_secret", "callback_url");

  public Event {
    if (eventId == null
        || tenantId == null
        || occurredAt == null
        || schemaVersion < 1
        || eventType == null
        || !eventType.matches("[a-z][a-z0-9._-]{0,159}")
        || producer == null
        || !producer.matches("[a-z][a-z0-9-]{0,63}")
        || (aggregateId == null) != (aggregateVersion == null)
        || aggregateId != null && (aggregateId.length() > 160 || aggregateVersion < 0)
        || traceparent != null
            && !traceparent.matches("00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-0[01]")
        || data == null) throw new IllegalArgumentException("事件字段不合法");
    data = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    validate(JSON.valueToTree(data), 0);
  }

  public byte[] encode() {
    validate(JSON.valueToTree(data), 0);
    byte[] raw = JSON.writeValueAsBytes(this);
    if (raw.length > MAX_BYTES) throw new IllegalArgumentException("事件超过大小上限");
    return raw;
  }

  public static Event decode(byte[] raw) {
    if (raw == null || raw.length > MAX_BYTES) throw new IllegalArgumentException("事件超过大小上限");
    // 禁止把字符串/布尔/小数宽松转换成版本号；与 Python strict int 一致。
    var tree = JSON.readTree(raw);
    if (!tree.path("occurred_at").isString()
        || !tree.path("occurred_at").asString().matches(".*(Z|[+]00:00)$"))
      throw new IllegalArgumentException("事件时间必须为 UTC 字符串");
    if (!tree.path("schema_version").isIntegralNumber()
        || !tree.path("aggregate_version").isMissingNode()
            && !tree.path("aggregate_version").isNull()
            && !tree.path("aggregate_version").isIntegralNumber())
      throw new IllegalArgumentException("事件版本必须为整数");
    return JSON.treeToValue(tree, Event.class);
  }

  private static void validate(JsonNode value, int depth) {
    if (depth > 32) throw new IllegalArgumentException("事件嵌套过深");
    if (value.isObject())
      value
          .properties()
          .forEach(
              entry -> {
                if (FORBIDDEN.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT)))
                  throw new IllegalArgumentException("事件不能包含凭据或任意回调字段");
                validate(entry.getValue(), depth + 1);
              });
    else if (value.isArray()) value.forEach(item -> validate(item, depth + 1));
  }
}
