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
        .onErrorResume(
            Exception.class,
            failure -> {
              if (!(failure instanceof PlatformException)
                  && !ApiErrors.accepts(exchange.getRequest().getPath().value()))
                return Mono.error(failure);
              PlatformException error =
                  failure instanceof org.springframework.web.bind.support.WebExchangeBindException
                      ? new PlatformException("VALIDATION_ERROR", "请求参数不合法", 400)
                      : ApiErrors.classify(failure);
              if (exchange.getResponse().isCommitted()) {
                return Mono.error(failure);
              }
              exchange
                  .getResponse()
                  .setStatusCode(org.springframework.http.HttpStatusCode.valueOf(error.status()));
              exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
              exchange.getResponse().getHeaders().putAll(ApiErrors.headers(failure));
              if (error.status() == 500)
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("请求处理失败 {}", ApiErrors.diagnostic(failure));
              return exchange
                  .getResponse()
                  .writeWith(
                      Mono.just(
                          exchange
                              .getResponse()
                              .bufferFactory()
                              .wrap(RequestContexts.error(error, context))));
            })
        .contextWrite(reactor -> reactor.put(CallContext.class, context));
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
