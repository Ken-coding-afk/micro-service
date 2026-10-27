package com.ken.platform.gateway.security;

import com.ken.platform.foundation.http.CallContext;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 固定 JSON/挑战头，不回显内省响应或异常正文。 */
public final class GatewayErrors
    implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException failure) {
    return write(exchange, failure instanceof AuthenticationServiceException ? 503 : 401);
  }

  @Override
  public Mono<Void> handle(
      ServerWebExchange exchange,
      org.springframework.security.access.AccessDeniedException failure) {
    return write(exchange, 403);
  }

  private static Mono<Void> write(ServerWebExchange exchange, int status) {
    return Mono.deferContextual(
        reactor -> {
          if (exchange.getResponse().isCommitted())
            return Mono.error(new IllegalStateException("认证响应已提交"));
          CallContext context =
              reactor.getOrDefault(
                  CallContext.class, new CallContext(null, null, null, null, null, null));
          var response = exchange.getResponse();
          response.setStatusCode(HttpStatusCode.valueOf(status));
          response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
          response.getHeaders().setCacheControl("no-store");
          response.getHeaders().set("X-Request-Id", context.requestId());
          if (status == 401)
            response.getHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
          if (status == 403)
            response.getHeaders().set("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
          var body = new LinkedHashMap<String, Object>();
          body.put(
              "code",
              status == 503
                  ? "IDENTITY_UNAVAILABLE"
                  : status == 401 ? "INVALID_CREDENTIALS" : "FORBIDDEN");
          body.put("message", status == 503 ? "身份内省暂不可用" : status == 401 ? "访问令牌无效" : "无权访问");
          body.put("data", null);
          body.put("request_id", context.requestId());
          return response.writeWith(
              Mono.just(response.bufferFactory().wrap(JSON.writeValueAsBytes(body))));
        });
  }
}
