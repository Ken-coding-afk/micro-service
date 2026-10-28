package com.ken.platform.gateway.security;

import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** WebFlux 的预检短路前，沿既有身份路由把精确 token POST 预检交给身份服务判定。 */
@Component
public final class GatewayTokenPreflightFilter implements WebFilter, Ordered {
  private final RouteLocator routes;
  private final FilteringWebHandler handler;

  public GatewayTokenPreflightFilter(RouteLocator routes, FilteringWebHandler handler) {
    this.routes = routes;
    this.handler = handler;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var request = exchange.getRequest();
    if (!request.getPath().value().equals("/oauth2/token")
        || !CorsUtils.isPreFlightRequest(request)
        || !HttpMethod.POST.equals(request.getHeaders().getAccessControlRequestMethod()))
      return chain.filter(exchange);
    return routes
        .getRoutes()
        .filter(route -> Boolean.TRUE.equals(route.getMetadata().get("platform.identity-public")))
        .filterWhen(route -> route.getPredicate().apply(exchange))
        .next()
        .map(
            route -> {
              exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
              return handler.handle(exchange);
            })
        .defaultIfEmpty(Mono.defer(() -> chain.filter(exchange)))
        .flatMap(work -> work);
  }

  @Override
  public int getOrder() {
    return 0;
  }
}
