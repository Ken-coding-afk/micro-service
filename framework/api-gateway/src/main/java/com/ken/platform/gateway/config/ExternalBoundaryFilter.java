package com.ken.platform.gateway.config;

import com.ken.platform.foundation.http.PlatformException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/** 外部请求在路由与认证前移除可伪造身份；内部接口不经公网 Gateway 暴露。 */
@Component
public final class ExternalBoundaryFilter implements WebFilter, Ordered {
  private static final Set<String> IDENTITY_HEADERS =
      Set.of(
          "login-user",
          "remote-user",
          "x-remote-user",
          "x-user-id",
          "x-tenant-id",
          "x-principal-id",
          "x-principal-type",
          "x-client-id",
          "x-roles",
          "x-permissions",
          "x-data-scopes",
          "x-service-authorization",
          "x-forwarded-user",
          "x-forwarded-tenant");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    // defer 保证错误也经过上层 Reactor 上下文错误适配，而非装配时提前抛出。
    return Mono.defer(
        () -> {
          String raw = exchange.getRequest().getURI().getRawPath();
          String path;
          try {
            path = UriUtils.decode(raw, StandardCharsets.UTF_8);
          } catch (IllegalArgumentException malformed) {
            throw invalid();
          }
          // 固定 API 无 matrix/反斜杠/双重编码路径；拒绝代理与下游解释不一致的入口。
          if (path.contains("\\")
              || path.contains(";")
              || path.contains("%")
              || path.contains("//")
              || path.chars().anyMatch(Character::isISOControl)
              || java.util.Arrays.stream(path.split("/"))
                  .anyMatch(segment -> segment.equals(".") || segment.equals("..")))
            throw invalid();
          if (path.equals("/internal") || path.startsWith("/internal/"))
            return Mono.error(new PlatformException("FORBIDDEN", "内部接口不允许通过公开入口访问", 403));
          var request =
              exchange
                  .getRequest()
                  .mutate()
                  .headers(
                      headers -> {
                        for (String key : java.util.List.copyOf(headers.headerNames())) {
                          if (IDENTITY_HEADERS.contains(key.toLowerCase(Locale.ROOT))
                              || key.toLowerCase(Locale.ROOT).startsWith("x-auth-"))
                            headers.remove(key);
                        }
                      })
                  .build();
          return chain.filter(exchange.mutate().request(request).build());
        });
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_REQUEST", "请求路径不合法", 400);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }
}
