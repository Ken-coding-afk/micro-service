package com.ken.platform.gateway.config;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import java.net.URI;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 连接失败剔除已选择实例；此过滤器不重放任何请求。 */
@Component
public final class FailedInstanceFilter implements GlobalFilter, Ordered {
  private final ServiceDiscovery discovery;

  public FailedInstanceFilter(ServiceDiscovery discovery) {
    this.discovery = discovery;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain
        .filter(exchange)
        .doOnError(
            failure -> {
              // 本地池容量、调用取消、业务保护错误不是对端失效证据。
              if (!(failure instanceof java.io.IOException)
                  && !(failure instanceof io.netty.channel.ConnectTimeoutException)
                  && !(failure instanceof io.netty.handler.timeout.ReadTimeoutException)) return;
              Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
              URI selected = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
              if (route != null
                  && "lb".equals(route.getUri().getScheme())
                  && selected != null
                  && selected.getHost() != null
                  && selected.getPort() > 0
                  && ("http".equals(selected.getScheme())
                      || "https".equals(selected.getScheme()))) {
                discovery.invalidate(
                    route.getUri().getHost(),
                    new ServiceInstance(
                        selected.getHost(), selected.getPort(), selected.getScheme()));
              }
            });
  }

  @Override
  public int getOrder() {
    return ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 1;
  }
}
