package com.ken.platform.foundation.http;

import java.time.Instant;
import java.util.Objects;

/** Servlet/任务线程的词法作用域；响应式调用使用显式上下文或 Reactor Context。 */
public final class CallContexts {
  private static final ThreadLocal<CallContext> CURRENT = new ThreadLocal<>();

  private CallContexts() {}

  public static CallContext current() {
    return CURRENT.get();
  }

  public static Scope bind(CallContext next) {
    CallContext parent = CURRENT.get();
    if (parent != null) {
      if (parent.tenantId() != null && !Objects.equals(parent.tenantId(), next.tenantId())
          || parent.principalId() != null
              && !Objects.equals(parent.principalId(), next.principalId())) {
        throw new IllegalArgumentException("不能在嵌套调用中切换可信身份");
      }
      Instant deadline = next.deadline();
      if (parent.deadline() != null && (deadline == null || parent.deadline().isBefore(deadline))) {
        next =
            new CallContext(
                next.requestId(),
                next.tenantId(),
                next.principalId(),
                next.accessToken(),
                next.traceparent(),
                parent.deadline());
      }
    }
    CURRENT.set(next);
    return new Scope(parent);
  }

  public static final class Scope implements AutoCloseable {
    private final CallContext previous;
    private boolean closed;

    private Scope(CallContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (!closed) {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
        closed = true;
      }
    }
  }
}
