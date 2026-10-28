package com.ken.platform.foundation.http;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class OutboundCallBudgetTest {
  @Test
  void effectiveDeadlineAndHeadersRespectLocalAndParentBudgets() {
    var now = Instant.parse("2026-10-28T00:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    for (int parent : new int[] {1, 10}) {
      var call =
          new CallContext(
              "id",
              null,
              null,
              "token",
              "00-11111111111111111111111111111111-2222222222222222-01",
              now.plusSeconds(parent));
      var budget = new OutboundCallBudget(clock, Duration.ofSeconds(2), call);
      var headers = new HttpHeaders();
      headers.setBasicAuth("client", "secret");
      budget.writeHeaders(headers, call);
      assertThat(headers.getFirst("X-Request-Deadline"))
          .isEqualTo(Long.toString(now.plusSeconds(parent == 1 ? 1 : 2).toEpochMilli()));
      assertThat(headers.getFirst("X-Request-Id")).isEqualTo("id");
      assertThat(headers.getFirst("traceparent"))
          .isEqualTo("00-11111111111111111111111111111111-2222222222222222-01");
      assertThat(headers.getFirst("Authorization")).startsWith("Basic ");
      budget.writeHeaders(headers, null);
      assertThat(headers.getFirst("X-Request-Id")).isNull();
      assertThat(headers.getFirst("traceparent")).isNull();
    }
  }

  @Test
  void expiredBudgetAndMonotonicTimeCannotBeExtendedByFixedWallClock() throws Exception {
    var clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    var expired = new CallContext("id", null, null, null, null, Instant.MIN);
    assertThat(new OutboundCallBudget(clock, Duration.ofSeconds(1), expired).remaining()).isZero();
    var budget = new OutboundCallBudget(clock, Duration.ofMillis(20), null);
    Thread.sleep(40);
    assertThat(budget.remaining()).isZero();
  }
}
