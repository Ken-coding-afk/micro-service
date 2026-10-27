package com.ken.platform.foundation.resilience;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.ken.platform.foundation.http.HttpProtection;
import com.ken.platform.foundation.http.PlatformException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅适配公开 AsyncEntry API，不实现自己的熔断状态机。 */
public final class SentinelProtection implements HttpProtection {
  private final java.util.Set<String> observed = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @Override
  public Lease enter(String resource) {
    boolean inbound = resource.equals("http.inbound");
    // 未声明的依赖不生成 Sentinel 指标，避免将调用参数变成无界资源名。
    if (!inbound
        && FlowRuleManager.getRules().stream().noneMatch(r -> r.getResource().equals(resource))
        && DegradeRuleManager.getRules().stream()
            .noneMatch(r -> r.getResource().equals(resource))) {
      return UNPROTECTED;
    }
    try {
      if (observed.add(resource))
        io.micrometer.core.instrument.Gauge.builder(
                "platform.protection.active",
                resource,
                name -> {
                  var node =
                      com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot
                          .getClusterNode(name);
                  return node == null ? 0 : node.curThreadNum();
                })
            .tag("resource", resource)
            .register(io.micrometer.core.instrument.Metrics.globalRegistry);
      AsyncEntry entry = SphU.asyncEntry(resource, inbound ? EntryType.IN : EntryType.OUT);
      return new Lease() {
        private final AtomicBoolean closed = new AtomicBoolean();

        public void failed(Throwable failure) {
          Tracer.traceEntry(failure, entry);
          io.micrometer.core.instrument.Metrics.counter(
                  "platform.protection.failures", "resource", resource)
              .increment();
        }

        public void close() {
          if (closed.compareAndSet(false, true)) entry.exit();
        }
      };
    } catch (BlockException blocked) {
      String code =
          blocked instanceof DegradeException
              ? "DEPENDENCY_CIRCUIT_OPEN"
              : inbound ? "SERVICE_BUSY" : "DEPENDENCY_BUSY";
      io.micrometer.core.instrument.Metrics.counter(
              "platform.protection.rejected", "resource", resource, "reason", code)
          .increment();
      throw new PlatformException(code, "服务保护策略拒绝本次请求", 503);
    }
  }
}
