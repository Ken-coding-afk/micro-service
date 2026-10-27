package com.ken.platform.foundation.discovery;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Gateway 使用同一有界目录；网络初始化移出 event loop，不叠加 SCA 默认无限缓存。 */
public final class BoundedServiceInstanceListSupplier implements ServiceInstanceListSupplier {
  private final String service;
  private final ServiceDiscovery discovery;
  private final NacosRuntime runtime;
  private final Clock clock;
  private final RuntimeParameters parameters;

  public BoundedServiceInstanceListSupplier(
      String service,
      ServiceDiscovery discovery,
      NacosRuntime runtime,
      Clock clock,
      RuntimeParameters parameters) {
    if (service == null || !service.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("负载均衡服务名不合法");
    }
    this.service = service;
    this.discovery = discovery;
    this.runtime = runtime;
    this.clock = clock;
    this.parameters = parameters;
  }

  @Override
  public String getServiceId() {
    return service;
  }

  @Override
  public Flux<List<org.springframework.cloud.client.ServiceInstance>> get() {
    return Mono.deferContextual(
            context -> {
              Instant deadline =
                  clock.instant().plusMillis((long) (parameters.responseBudgetSeconds() * 1000));
              CallContext request = context.getOrDefault(CallContext.class, null);
              if (request != null
                  && request.deadline() != null
                  && request.deadline().isBefore(deadline)) {
                deadline = request.deadline();
              }
              Duration remaining = Duration.between(clock.instant(), deadline);
              if (remaining.isNegative() || remaining.isZero()) {
                return Mono.<List<org.springframework.cloud.client.ServiceInstance>>error(
                    timeout());
              }
              return Mono.fromCallable(
                      () -> {
                        runtime.watch(service);
                        return discovery.instances(service).stream()
                            .<org.springframework.cloud.client.ServiceInstance>map(
                                instance ->
                                    new DefaultServiceInstance(
                                        service + ":" + instance.host() + ":" + instance.port(),
                                        service,
                                        instance.host(),
                                        instance.port(),
                                        instance.scheme().equals("https"),
                                        Map.of("scheme", instance.scheme())))
                            .toList();
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .timeout(remaining)
                  .onErrorMap(java.util.concurrent.TimeoutException.class, ignored -> timeout())
                  .onErrorMap(
                      com.alibaba.nacos.api.exception.NacosException.class,
                      ignored -> unavailable())
                  .onErrorMap(IllegalArgumentException.class, ignored -> unavailable());
            })
        .flux();
  }

  private static PlatformException timeout() {
    return new PlatformException("UPSTREAM_TIMEOUT", "服务发现超过调用剩余预算", 504);
  }

  private static PlatformException unavailable() {
    return new PlatformException("DEPENDENCY_UNAVAILABLE", "服务目录不可用或端点无效", 503);
  }
}
