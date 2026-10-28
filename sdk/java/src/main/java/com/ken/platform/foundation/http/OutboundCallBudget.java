package com.ken.platform.foundation.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;

/** 一次逻辑调用共享预算；不接管协议、凭据或客户端资源。 */
public final class OutboundCallBudget {
  private final Clock clock;
  private final Instant deadline;
  private final long monotonicDeadline;

  public OutboundCallBudget(Clock clock, Duration localBudget, CallContext context) {
    this.clock = clock;
    Instant now = clock.instant();
    Instant local = now.plus(localBudget);
    deadline =
        context != null && context.deadline() != null && context.deadline().isBefore(local)
            ? context.deadline()
            : local;
    Duration available = Duration.between(now, deadline);
    monotonicDeadline =
        System.nanoTime()
            + (available.isNegative() || available.isZero() ? 0 : available.toNanos());
  }

  public Duration remaining() {
    Duration wall = Duration.between(clock.instant(), deadline);
    Duration monotonic = Duration.ofNanos(monotonicDeadline - System.nanoTime());
    Duration remaining = wall.compareTo(monotonic) < 0 ? wall : monotonic;
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  public void writeHeaders(HttpHeaders headers, CallContext context) {
    headers.remove("X-Request-Id");
    headers.remove("traceparent");
    headers.set("X-Request-Deadline", Long.toString(deadline.toEpochMilli()));
    if (context != null) {
      headers.set("X-Request-Id", context.requestId());
      if (context.traceparent() != null) headers.set("traceparent", context.traceparent());
    }
  }
}
