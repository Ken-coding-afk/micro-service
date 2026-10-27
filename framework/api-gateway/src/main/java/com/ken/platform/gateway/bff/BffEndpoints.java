package com.ken.platform.gateway.bff;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 浏览器仅取得 CSRF 材料；授权凭证一直保存在服务端会话。 */
@RestController
@ConditionalOnProperty(name = "GATEWAY_BFF_CLIENTS_SECRET_FILE")
public final class BffEndpoints {
  private final BffTokens tokens;

  public BffEndpoints(BffTokens tokens) {
    this.tokens = tokens;
  }

  @GetMapping({"/bff/identity/platform/csrf", "/bff/identity/tenant/csrf", "/bff/web/csrf"})
  Mono<Map<String, Object>> csrf(ServerWebExchange exchange) {
    Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
    if (token == null) return Mono.error(new IllegalStateException("CSRF 组件未装配"));
    return token.flatMap(
        value ->
            ok(exchange, Map.of("header_name", value.getHeaderName(), "token", value.getToken())));
  }

  @PostMapping({"/bff/identity/platform/logout", "/bff/identity/tenant/logout", "/bff/web/logout"})
  Mono<Map<String, Object>> logout(ServerWebExchange exchange, Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthenticationToken current))
      return Mono.error(
          new com.ken.platform.foundation.http.PlatformException(
              "INVALID_CREDENTIALS", "请重新登录", 401));
    return tokens.logout(exchange, current).then(ok(exchange, Map.of("signed_out", true)));
  }

  @GetMapping({
    "/bff/identity/platform/logged-out",
    "/bff/identity/tenant/logged-out",
    "/bff/web/logged-out"
  })
  Mono<Void> loggedOut(ServerWebExchange exchange) {
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.SEE_OTHER);
    exchange
        .getResponse()
        .getHeaders()
        .setLocation(java.net.URI.create(namespace == BffNamespace.WEB ? "/" : "/identity/admin/"));
    return exchange.getResponse().setComplete();
  }

  private Mono<Map<String, Object>> ok(ServerWebExchange exchange, Object data) {
    return Mono.deferContextual(
        context -> {
          var call =
              context.getOrDefault(
                  com.ken.platform.foundation.http.CallContext.class,
                  new com.ken.platform.foundation.http.CallContext(
                      null, null, null, null, null, null));
          return Mono.just(
              Map.of(
                  "code",
                  "OK",
                  "message",
                  "success",
                  "data",
                  data,
                  "request_id",
                  call.requestId()));
        });
  }
}
