package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 仅对带固定 BFF 元数据的路由取服务端 token；流式转发仍由 Gateway 原生管线完成。 */
public final class BffRelay implements GlobalFilter, Ordered {
  private final BffTokens tokens;
  private final BffIdentityClient identity;

  public BffRelay(BffTokens tokens, BffIdentityClient identity) {
    this.tokens = tokens;
    this.identity = identity;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route == null || !(route.getMetadata().get("bff.namespace") instanceof String selected))
      return chain.filter(exchange);
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    if (namespace == null || !namespace.name().equals(selected)) return Mono.error(invalid());
    if (namespace != BffNamespace.WEB) {
      String prefix = namespace.path() + "/api/", path = exchange.getRequest().getPath().value();
      if (!path.startsWith(prefix)) return Mono.error(invalid());
      String resource = path.substring(prefix.length());
      boolean platformResource = resource.equals("platform") || resource.startsWith("platform/");
      if (namespace == BffNamespace.TENANT && platformResource
          || namespace == BffNamespace.PLATFORM
              && !platformResource
              && !resource.equals("me")
              && !resource.startsWith("me/"))
        return Mono.error(new PlatformException("FORBIDDEN", "当前管理入口不允许访问该对象", 403));
    }
    return exchange
        .getPrincipal()
        .ofType(OAuth2AuthenticationToken.class)
        .switchIfEmpty(Mono.error(invalid()))
        .flatMap(
            authentication ->
                tokens
                    .current(exchange, authentication)
                    .flatMap(
                        client ->
                            identity
                                .application(
                                    client.getAccessToken().getTokenValue(),
                                    client.getClientRegistration())
                                .flatMap(
                                    current -> {
                                      if (!current.subject().equals(authentication.getName())
                                          || !current
                                              .type()
                                              .equals(
                                                  authentication
                                                      .getPrincipal()
                                                      .getAttribute("principal_type"))
                                          || !java.util.Objects.equals(
                                              current.tenant() == null
                                                  ? null
                                                  : current.tenant().toString(),
                                              authentication
                                                  .getPrincipal()
                                                  .getAttribute("tenant_id")))
                                        return Mono.error(invalid());
                                      if (!current
                                          .audiences()
                                          .contains(route.getMetadata().get("bff.audience")))
                                        return Mono.error(
                                            new PlatformException(
                                                "FORBIDDEN", "当前应用未获准访问目标资源", 403));
                                      return Mono.deferContextual(
                                          context -> {
                                            CallContext parent =
                                                context.getOrDefault(CallContext.class, null);
                                            if (parent == null)
                                              return Mono.error(
                                                  new IllegalStateException("缺少 BFF 请求上下文"));
                                            exchange
                                                .getAttributes()
                                                .put(
                                                    com.ken.platform.gateway.traffic.RateSubject
                                                        .ATTRIBUTE,
                                                    new com.ken.platform.gateway.traffic
                                                        .RateSubject(
                                                        current.type(),
                                                        current.tenant() == null
                                                            ? null
                                                            : current.tenant().toString(),
                                                        current.subject(),
                                                        client
                                                            .getClientRegistration()
                                                            .getClientId()));
                                            String token = client.getAccessToken().getTokenValue();
                                            var call =
                                                new CallContext(
                                                    parent.requestId(),
                                                    current.tenant(),
                                                    current.subject(),
                                                    token,
                                                    parent.traceparent(),
                                                    parent.deadline());
                                            var request =
                                                exchange
                                                    .getRequest()
                                                    .mutate()
                                                    .headers(
                                                        headers -> {
                                                          headers.remove("Cookie");
                                                          headers.setBearerAuth(token);
                                                        })
                                                    .build();
                                            return chain
                                                .filter(exchange.mutate().request(request).build())
                                                .contextWrite(
                                                    values -> values.put(CallContext.class, call));
                                          });
                                    })))
        .onErrorResume(
            PlatformException.class,
            error ->
                error.status() == 401
                    ? exchange
                        .getSession()
                        .flatMap(org.springframework.web.server.WebSession::invalidate)
                        .then(Mono.error(error))
                    : Mono.error(error));
  }

  @Override
  public int getOrder() {
    return -100;
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "当前应用登录已失效", 401);
  }
}
