package com.ken.platform.gateway.security;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.gateway.traffic.RateSubject;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 原生选路后认证；不能用另一次路径匹配代替实际目标。 */
public final class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
  private final GatewayIntrospectionClients clients;

  public GatewayAuthenticationFilter(GatewayIntrospectionClients clients) {
    this.clients = clients;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route != null && Boolean.TRUE.equals(route.getMetadata().get("platform.identity-public")))
      return chain.filter(exchange);
    if (route == null || !(route.getMetadata().get("platform.audience") instanceof String audience))
      return Mono.error(new PlatformException("FORBIDDEN", "目标 API 未登记", 403));
    if (route.getMetadata().containsKey("bff.namespace")) {
      if (!(exchange.getAttribute(RateSubject.ATTRIBUTE) instanceof RateSubject))
        return Mono.error(new PlatformException("INVALID_CREDENTIALS", "BFF 身份未验证", 401));
      return chain.filter(exchange);
    }
    return Mono.defer(
            () -> {
              var values = exchange.getRequest().getHeaders().get("Authorization");
              if (values == null
                  || values.size() != 1
                  || values.getFirst().length() > 8199
                  || !values.getFirst().matches("(?i)Bearer [A-Za-z0-9._~+/-]+=*"))
                return Mono.error(new InvalidBearerTokenException("访问令牌无效"));
              var introspector = clients.forAudience(audience);
              var manager =
                  new OpaqueTokenReactiveAuthenticationManager(
                      token ->
                          Mono.deferContextual(
                              context ->
                                  Mono.fromFuture(
                                          introspector.introspectAsync(
                                              token, context.getOrDefault(CallContext.class, null)))
                                      .cast(OAuth2AuthenticatedPrincipal.class)));
              return manager
                  .authenticate(new BearerTokenAuthenticationToken(values.getFirst().substring(7)))
                  .flatMap(
                      authentication -> {
                        exchange
                            .getAttributes()
                            .put(
                                RateSubject.ATTRIBUTE,
                                RateSubject.from((CurrentPrincipal) authentication.getPrincipal()));
                        return chain.filter(
                            exchange.mutate().principal(Mono.just(authentication)).build());
                      });
            })
        .onErrorResume(
            org.springframework.security.core.AuthenticationException.class,
            error -> new GatewayErrors().commence(exchange, error));
  }

  @Override
  public int getOrder() {
    return -90;
  }
}
