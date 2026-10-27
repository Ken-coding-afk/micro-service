package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** 实际 Redis 上的官方请求仓库与跨节点一次性占用；本类不模拟授权码签发成功。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class BffAuthorizationRequestsRepositoryTest {
  private BffRedisFixture redis;
  private BffSessions sessions;
  private BffAuthorizationRequestsRepository repository;
  private final String state = "state-" + UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    redis = new BffRedisFixture();
    sessions = new BffSessions(redis.sessions, redis.project, "local", false, Clock.systemUTC());
    repository = new BffAuthorizationRequestsRepository(redis.strings, redis.project, "local");
  }

  @AfterEach
  void close() {
    if (redis != null) redis.close();
  }

  private ServerWebExchange exchange(BffNamespace namespace, String id, String expectedState) {
    var request =
        MockServerHttpRequest.get(
            "http://localhost"
                + namespace.path()
                + "/callback?code=technical-code&state="
                + expectedState);
    if (id != null) request.cookie(new HttpCookie(sessions.cookieName(namespace), id));
    var mock = MockServerWebExchange.from(request);
    WebSession session = sessions.getSession(mock).block();
    return new ServerWebExchangeDecorator(mock) {
      @Override
      public Mono<WebSession> getSession() {
        return Mono.just(session);
      }
    };
  }

  private String save() {
    var exchange = exchange(BffNamespace.TENANT, null, state);
    var request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost/oauth2/authorize")
            .clientId("identity-admin-tenant")
            .redirectUri("http://localhost/bff/identity/tenant/callback")
            .state(state)
            .build();
    repository.saveAuthorizationRequest(request, exchange).block();
    exchange.getResponse().setComplete().block();
    return exchange.getSession().block().getId();
  }

  @Test
  void twoNodesCannotRedeemSameSessionStateTwice() {
    String id = save();
    var first = exchange(BffNamespace.TENANT, id, state);
    var second = exchange(BffNamespace.TENANT, id, state);
    var other = new BffAuthorizationRequestsRepository(redis.strings, redis.project, "local");
    var results =
        Mono.zip(
                repository.removeAuthorizationRequest(first).materialize(),
                other.removeAuthorizationRequest(second).materialize())
            .block(Duration.ofSeconds(5));
    assertThat(results).isNotNull();
    var signals = java.util.List.of(results.getT1(), results.getT2());
    assertThat(signals.stream().filter(reactor.core.publisher.Signal::hasValue)).hasSize(1);
    assertThat(signals.stream().filter(reactor.core.publisher.Signal::isOnError)).hasSize(1);
    assertThat(
            signals.stream()
                .filter(reactor.core.publisher.Signal::isOnError)
                .findFirst()
                .orElseThrow()
                .getThrowable())
        .isInstanceOf(OAuth2AuthenticationException.class);
    var keys =
        redis
            .strings
            .keys(redis.project + ":local:api-gateway:global:bff-callback:*")
            .collectList()
            .block();
    assertThat(keys).hasSize(1).allMatch(key -> !key.contains(state));
  }

  @Test
  void wrongStateAndCopiedIdInDifferentNamespaceCannotConsumeValidRequest() {
    String id = save();
    assertThat(
            repository
                .removeAuthorizationRequest(exchange(BffNamespace.TENANT, id, "wrong-state"))
                .block())
        .isNull();
    assertThat(
            repository
                .removeAuthorizationRequest(exchange(BffNamespace.PLATFORM, id, state))
                .block())
        .isNull();
    assertThat(
            redis
                .strings
                .keys(redis.project + ":local:api-gateway:global:bff-callback:*")
                .collectList()
                .block())
        .isEmpty();
    assertThat(
            repository.removeAuthorizationRequest(exchange(BffNamespace.TENANT, id, state)).block())
        .isNotNull();
  }

  @Test
  void replayedCallbackCannotReusePendingRequestEvenBeforeResponseCommit() {
    String id = save();
    assertThat(
            repository.removeAuthorizationRequest(exchange(BffNamespace.TENANT, id, state)).block())
        .isNotNull();
    // 第一份请求尚未提交 Session 清理，第二份确实仍可从 Redis 读到旧待处理对象。
    assertThatThrownBy(
            () ->
                repository
                    .removeAuthorizationRequest(exchange(BffNamespace.TENANT, id, state))
                    .block())
        .isInstanceOf(OAuth2AuthenticationException.class);
  }
}
