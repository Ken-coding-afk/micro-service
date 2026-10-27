package com.ken.platform.gateway.traffic;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 包含认证、连接和响应体的普通请求总预算；流式路由不套普通截止时间。 */
public final class GatewayRequestBudget implements GlobalFilter, Ordered {
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public GatewayRequestBudget() {
    this(io.micrometer.core.instrument.Metrics.globalRegistry);
  }

  public GatewayRequestBudget(io.micrometer.core.instrument.MeterRegistry metrics) {
    this.metrics = metrics;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    Mono<Void> work = Mono.defer(() -> chain.filter(exchange));
    if (route != null
        && !Boolean.TRUE.equals(route.getMetadata().get("platform.identity-public"))
        && !Boolean.TRUE.equals(route.getMetadata().get("platform.streaming"))) {
      work =
          work.timeout(
              Duration.ofSeconds(15),
              Mono.defer(
                  () -> {
                    metrics
                        .counter("platform.gateway.budget.timeout", "route", route.getId())
                        .increment();
                    return Mono.error(new PlatformException("UPSTREAM_TIMEOUT", "网关请求总预算已耗尽", 504));
                  }));
    }
    return work.doOnError(
        PlatformRedisRateLimiter.Rejected.class,
        rejected -> {
          if (!exchange.getResponse().isCommitted())
            exchange
                .getResponse()
                .getHeaders()
                .set("Retry-After", Long.toString(rejected.retryAfter()));
        });
  }

  @Override
  public int getOrder() {
    return -200;
  }
}
