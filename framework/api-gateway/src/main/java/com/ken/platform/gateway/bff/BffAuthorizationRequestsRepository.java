package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.http.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionOAuth2ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 官方 state/会话核验后原子占用一次性回调，防止双节点同时兑换同一授权码。 */
public final class BffAuthorizationRequestsRepository
    implements ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  private final WebSessionOAuth2ServerAuthorizationRequestRepository delegate =
      new WebSessionOAuth2ServerAuthorizationRequestRepository();
  private final ReactiveStringRedisTemplate redis;
  private final String prefix;

  public BffAuthorizationRequestsRepository(
      ReactiveStringRedisTemplate redis, String project, String environment) {
    this.redis = redis;
    prefix = project + ":" + environment + ":api-gateway:global:bff-callback:";
  }

  @Override
  public Mono<OAuth2AuthorizationRequest> loadAuthorizationRequest(ServerWebExchange exchange) {
    return delegate.loadAuthorizationRequest(exchange);
  }

  @Override
  public Mono<Void> saveAuthorizationRequest(
      OAuth2AuthorizationRequest request, ServerWebExchange exchange) {
    return delegate.saveAuthorizationRequest(request, exchange);
  }

  @Override
  public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
    return delegate
        .removeAuthorizationRequest(exchange)
        .flatMap(
            request ->
                exchange
                    .getSession()
                    .flatMap(
                        session -> {
                          var namespace =
                              BffNamespace.forPath(exchange.getRequest().getPath().value());
                          if (namespace == null)
                            return Mono.error(
                                new OAuth2AuthenticationException(
                                    "authorization_request_not_found"));
                          String key =
                              prefix
                                  + namespace.name()
                                  + ":"
                                  + session.getId()
                                  + ":"
                                  + digest(request.getState());
                          return redis
                              .opsForValue()
                              .setIfAbsent(key, "CONSUMED", BffSessions.MAX_ABSOLUTE)
                              .onErrorMap(
                                  error ->
                                      new PlatformException(
                                          "BFF_SESSION_UNAVAILABLE", "回调状态存储暂不可用", 503))
                              .flatMap(
                                  acquired ->
                                      Boolean.TRUE.equals(acquired)
                                          ? Mono.just(request)
                                          : Mono.error(
                                              new OAuth2AuthenticationException(
                                                  "authorization_request_not_found")));
                        }));
  }

  private static String digest(String state) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }
}
