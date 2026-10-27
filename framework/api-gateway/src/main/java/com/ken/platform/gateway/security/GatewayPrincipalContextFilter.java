package com.ken.platform.gateway.security;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.security.CurrentPrincipal;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 在原生选路与 GatewayAuthenticationFilter 后建立可信 Reactor Context；不写 ThreadLocal。 */
@Component
public final class GatewayPrincipalContextFilter implements GlobalFilter, Ordered {
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return Mono.deferContextual(
        reactor -> {
          CallContext parent = reactor.getOrDefault(CallContext.class, null);
          if (parent == null) return Mono.error(new IllegalStateException("Gateway 请求上下文未装配"));
          return exchange
              .getPrincipal()
              .map(
                  authentication -> {
                    if (authentication instanceof BearerTokenAuthentication bearer
                        && bearer.isAuthenticated()
                        && bearer.getPrincipal() instanceof CurrentPrincipal principal)
                      return new CallContext(
                          parent.requestId(),
                          principal.tenantId(),
                          principal.subject(),
                          bearer.getToken().getTokenValue(),
                          parent.traceparent(),
                          parent.deadline());
                    return parent;
                  })
              .defaultIfEmpty(parent)
              .flatMap(
                  context -> {
                    exchange.getAttributes().put(CallContext.class.getName(), context);
                    var request =
                        exchange
                            .getRequest()
                            .mutate()
                            .headers(
                                headers -> {
                                  headers.set("X-Request-Id", context.requestId());
                                  if (context.traceparent() == null) headers.remove("traceparent");
                                  else headers.set("traceparent", context.traceparent());
                                  if (context.deadline() != null)
                                    headers.set(
                                        "X-Request-Deadline",
                                        String.valueOf(context.deadline().toEpochMilli()));
                                })
                            .build();
                    return chain
                        .filter(exchange.mutate().request(request).build())
                        .contextWrite(values -> values.put(CallContext.class, context));
                  });
        });
  }

  @Override
  public int getOrder() {
    return -80;
  }
}
