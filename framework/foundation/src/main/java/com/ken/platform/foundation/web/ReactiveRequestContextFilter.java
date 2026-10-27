package com.ken.platform.foundation.web;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 每条响应链独立 Reactor Context；流已开始时不再发送第二个 JSON 响应。 */
public final class ReactiveRequestContextFilter implements WebFilter, Ordered {
  private final Clock clock;
  private final RuntimeParameters parameters;

  public ReactiveRequestContextFilter(Clock clock, RuntimeParameters parameters) {
    this.clock = clock;
    this.parameters = parameters;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var headers = exchange.getRequest().getHeaders();
    CallContext context =
        RequestContexts.incoming(
            headers.getFirst("X-Request-Id"),
            headers.getFirst("traceparent"),
            headers.getFirst("X-Request-Deadline"),
            clock,
            parameters.responseBudgetSeconds());
    exchange.getAttributes().put(RequestContexts.ATTRIBUTE, context);
    exchange.getResponse().getHeaders().set("X-Request-Id", context.requestId());
    return Mono.defer(() -> chain.filter(exchange))
        .contextWrite(reactor -> reactor.put(CallContext.class, context))
        .onErrorResume(
            PlatformException.class,
            error -> {
              if (exchange.getResponse().isCommitted()) {
                return Mono.error(error);
              }
              exchange
                  .getResponse()
                  .setStatusCode(org.springframework.http.HttpStatusCode.valueOf(error.status()));
              exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
              return exchange
                  .getResponse()
                  .writeWith(
                      Mono.just(
                          exchange
                              .getResponse()
                              .bufferFactory()
                              .wrap(RequestContexts.error(error, context))));
            });
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
