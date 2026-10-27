package com.ken.platform.foundation.resilience;

import com.ken.platform.foundation.http.HttpProtection;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class ReactiveAdmissionFilter implements WebFilter, Ordered {
  private final HttpProtection protection;

  public ReactiveAdmissionFilter(HttpProtection protection) {
    this.protection = protection;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!path.startsWith("/api/v1/") && !path.startsWith("/internal/v1/"))
      return chain.filter(exchange);
    return Mono.using(
        () -> protection.enter("http.inbound"),
        lease -> chain.filter(exchange),
        HttpProtection.Lease::close);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
