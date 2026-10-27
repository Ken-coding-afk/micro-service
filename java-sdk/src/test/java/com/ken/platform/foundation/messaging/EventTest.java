package com.ken.platform.foundation.messaging;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventTest {
  @Test
  void roundTripAndCredentialRejection() {
    var value =
        new Event(
            UUID.randomUUID(),
            "sample.changed",
            1,
            UUID.randomUUID(),
            "sample-service",
            Instant.parse("2026-10-27T00:00:00Z"),
            null,
            "item",
            1L,
            Map.of("amount", 1));
    assertThat(Event.decode(value.encode())).isEqualTo(value);
    assertThatThrownBy(
            () ->
                new Event(
                    value.eventId(),
                    value.eventType(),
                    1,
                    value.tenantId(),
                    value.producer(),
                    value.occurredAt(),
                    null,
                    null,
                    null,
                    Map.of("nested", Map.of("access_token", "not-allowed"))))
        .isInstanceOf(IllegalArgumentException.class);
    String body = new String(value.encode(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body).contains("\"occurred_at\":\"2026-10-27T00:00:00Z\"");
    assertThatThrownBy(
            () ->
                Event.decode(
                    body.replace("\"schema_version\":1", "\"schema_version\":true")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
