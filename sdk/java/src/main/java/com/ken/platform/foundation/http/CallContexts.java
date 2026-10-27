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
    var scope = new Scope(parent);
    replace(next);
    return scope;
  }

  private static void replace(CallContext context) {
    if (context == null) CURRENT.remove();
    else CURRENT.set(context);
    field("request_id", context == null ? null : context.requestId());
    field(
        "tenant_id",
        context == null || context.tenantId() == null ? null : context.tenantId().toString());
    field(
        "trace_id",
        context == null || context.traceparent() == null
            ? null
            : context.traceparent().substring(3, 35));
  }

  private static void field(String key, String value) {
    if (value == null) org.slf4j.MDC.remove(key);
    else org.slf4j.MDC.put(key, value);
  }

  /** 成熟上下文 SPI；应用启动时调用，仅投影日志字段，不把 token 放入 MDC。 */
  public static void enableReactorPropagation() {
    io.micrometer.context.ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            new io.micrometer.context.ThreadLocalAccessor<CallContext>() {
              public Object key() {
                return CallContext.class;
              }

              public CallContext getValue() {
                return current();
              }

              public void setValue(CallContext value) {
                replace(value);
              }

              public void setValue() {
                replace(null);
              }
            });
    reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
  }

  /** 用于显式跨 Executor 边界；不用于脱离请求后长期持有用户凭据。 */
  public static Runnable wrap(Runnable action) {
    Objects.requireNonNull(action);
    CallContext captured = current();
    return () -> {
      var previous = new Scope(current());
      replace(captured);
      try {
        action.run();
      } finally {
        previous.close();
      }
    };
  }

  public static Scope task(java.util.UUID tenant, String service, java.time.Duration budget) {
    if (current() != null) throw new IllegalArgumentException("独立任务必须从干净上下文启动");
    if (tenant == null
        || service == null
        || !service.matches("[a-z][a-z0-9-]{0,63}")
        || budget == null
        || budget.isNegative()
        || budget.isZero()
        || budget.compareTo(java.time.Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("任务必须声明合法租户、服务和不超过24小时的预算");
    return bind(
        new CallContext(
            null, tenant, "service:" + service, null, null, Instant.now().plus(budget)));
  }

  public static java.time.Duration remainingBudget(java.time.Duration fallback) {
    if (fallback == null || fallback.isNegative() || fallback.isZero())
      throw new IllegalArgumentException("预算必须为正数");
    var context = current();
    if (context == null || context.deadline() == null) return fallback;
    var remaining = java.time.Duration.between(Instant.now(), context.deadline());
    if (remaining.isNegative() || remaining.isZero()) throw expired();
    return remaining.compareTo(fallback) < 0 ? remaining : fallback;
  }

  public static void checkDeadline() {
    var context = current();
    if (context != null && context.deadline() != null && !context.deadline().isAfter(Instant.now()))
      throw expired();
  }

  private static PlatformException expired() {
    return new PlatformException("UPSTREAM_TIMEOUT", "调用预算已耗尽；写入结果可能需要核对", 504);
  }

  public static final class Scope implements AutoCloseable {
    private final CallContext previous;
    private final java.util.Map<String, String> previousMdc = new java.util.HashMap<>();
    private boolean closed;

    private Scope(CallContext previous) {
      this.previous = previous;
      for (var key : java.util.List.of("request_id", "tenant_id", "trace_id"))
        previousMdc.put(key, org.slf4j.MDC.get(key));
    }

    @Override
    public void close() {
      if (!closed) {
        replace(previous);
        previousMdc.forEach(CallContexts::field);
        closed = true;
      }
    }
  }
}
