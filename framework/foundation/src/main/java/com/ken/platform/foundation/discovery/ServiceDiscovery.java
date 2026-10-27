package com.ken.platform.foundation.discovery;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** 最后一次网络确认超过 60 秒后失败关闭；SDK 缓存命中不能调用 confirm。 */
public final class ServiceDiscovery {
  public static final Duration MAX_STALE = Duration.ofSeconds(60);
  private final LongSupplier nanoClock;
  private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> positions = new ConcurrentHashMap<>();

  private record Snapshot(List<ServiceInstance> instances, long confirmedAt) {}

  public ServiceDiscovery(LongSupplier nanoClock) {
    this.nanoClock = nanoClock;
  }

  public void confirm(String service, List<ServiceInstance> instances) {
    snapshots.put(service, new Snapshot(List.copyOf(instances), nanoClock.getAsLong()));
  }

  public List<ServiceInstance> instances(String service) {
    Snapshot snapshot = snapshots.get(service);
    if (snapshot == null
        || snapshot.instances().isEmpty()
        || nanoClock.getAsLong() - snapshot.confirmedAt() > MAX_STALE.toNanos()) {
      throw new PlatformException("DEPENDENCY_UNAVAILABLE", "没有未过期的可用服务实例", 503);
    }
    return snapshot.instances();
  }

  public ServiceInstance choose(String service) {
    List<ServiceInstance> instances = instances(service);
    int position =
        positions.computeIfAbsent(service, ignored -> new AtomicInteger()).getAndIncrement();
    return instances.get(Math.floorMod(position, instances.size()));
  }

  public void invalidate(String service, ServiceInstance instance) {
    snapshots.computeIfPresent(
        service,
        (ignored, snapshot) ->
            new Snapshot(
                snapshot.instances().stream().filter(item -> !item.equals(instance)).toList(),
                snapshot.confirmedAt()));
  }
}
