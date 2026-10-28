package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** 同一持久会话的刷新/退出串行；不接管结果未知的刷新，不自动重放客户端凭证写入。 */
public final class BffTokens {
  private static final DefaultRedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",
          Long.class);
  private final BffSessions sessions;
  private final ServerOAuth2AuthorizedClientRepository repository;
  private final ReactiveStringRedisTemplate locks;
  private final BffIdentityClient identity;
  private final DefaultReactiveOAuth2AuthorizedClientManager manager;
  private final Clock clock;
  private final String prefix;
  private final Duration waitBudget;

  public BffTokens(
      BffSessions sessions,
      ServerOAuth2AuthorizedClientRepository repository,
      ReactiveStringRedisTemplate locks,
      ReactiveClientRegistrationRepository registrations,
      BffIdentityClient identity,
      RuntimeParameters parameters,
      Clock clock,
      String project,
      String environment) {
    this.sessions = sessions;
    this.repository = repository;
    this.locks = locks;
    this.identity = identity;
    this.clock = clock;
    prefix = project + ":" + environment + ":api-gateway:global:bff-operation:";
    waitBudget = Duration.ofMillis(Math.max(1, (long) (parameters.responseBudgetSeconds() * 1000)));
    var refresh = new WebClientReactiveRefreshTokenTokenResponseClient();
    refresh.setWebClient(identity.web());
    manager = new DefaultReactiveOAuth2AuthorizedClientManager(registrations, repository);
    manager.setAuthorizedClientProvider(
        ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
            .refreshToken(
                grant ->
                    grant
                        .accessTokenResponseClient(refresh)
                        .clock(clock)
                        .clockSkew(Duration.ofSeconds(30)))
            .build());
  }

  public Mono<OAuth2AuthorizedClient> current(
      ServerWebExchange exchange, OAuth2AuthenticationToken authentication) {
    return load(exchange, authentication)
        .flatMap(
            client ->
                valid(client)
                    ? Mono.just(client)
                    : exclusive(
                        exchange,
                        authentication,
                        fresh ->
                            load(fresh, authentication)
                                .flatMap(
                                    latest -> {
                                      if (valid(latest)) return Mono.just(latest);
                                      var request =
                                          OAuth2AuthorizeRequest.withAuthorizedClient(latest)
                                              .principal(authentication)
                                              .attribute(ServerWebExchange.class.getName(), fresh)
                                              .build();
                                      return manager
                                          .authorize(request)
                                          .filter(this::valid)
                                          .switchIfEmpty(Mono.error(invalid()))
                                          .onErrorMap(BffTokens::classify)
                                          .flatMap(
                                              updated ->
                                                  fresh
                                                      .getSession()
                                                      .flatMap(
                                                          session ->
                                                              session.save().thenReturn(updated)));
                                    })))
        .onErrorMap(BffTokens::classify);
  }

  public Mono<Void> logout(ServerWebExchange exchange, OAuth2AuthenticationToken authentication) {
    return exclusive(
            exchange,
            authentication,
            fresh ->
                load(fresh, authentication)
                    .flatMap(
                        client -> {
                          Mono<Void> revoke =
                              client.getRefreshToken() == null
                                  ? Mono.empty()
                                  : identity.revoke(
                                      client.getClientRegistration(),
                                      client.getRefreshToken().getTokenValue());
                          return revoke
                              .then(
                                  identity.revoke(
                                      client.getClientRegistration(),
                                      client.getAccessToken().getTokenValue()))
                              .then(fresh.getSession().flatMap(WebSession::invalidate))
                              .then(exchange.getSession().flatMap(WebSession::invalidate))
                              .thenReturn(Boolean.TRUE);
                        }))
        .then()
        .onErrorMap(BffTokens::classify);
  }

  private Mono<OAuth2AuthorizedClient> load(
      ServerWebExchange exchange, OAuth2AuthenticationToken authentication) {
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    if (namespace == null
        || !namespace.name().equalsIgnoreCase(authentication.getAuthorizedClientRegistrationId()))
      return Mono.error(invalid());
    return repository
        .<OAuth2AuthorizedClient>loadAuthorizedClient(
            authentication.getAuthorizedClientRegistrationId(), authentication, exchange)
        .filter(
            client ->
                client.getClientRegistration().getClientId().equals(namespace.clientId())
                    && client.getPrincipalName().equals(authentication.getName()))
        .switchIfEmpty(Mono.error(invalid()));
  }

  private boolean valid(OAuth2AuthorizedClient client) {
    return client.getAccessToken().getExpiresAt() != null
        && clock.instant().plusSeconds(30).isBefore(client.getAccessToken().getExpiresAt());
  }

  private <T> Mono<T> exclusive(
      ServerWebExchange exchange,
      OAuth2AuthenticationToken authentication,
      Function<ServerWebExchange, Mono<T>> operation) {
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    if (namespace == null) return Mono.error(invalid());
    return exchange
        .getSession()
        .flatMap(
            original -> {
              String key = prefix + namespace.name() + ":" + original.getId(),
                  owner = UUID.randomUUID().toString();
              return Mono.usingWhen(
                  acquire(namespace, original.getId(), key, owner).timeout(waitBudget),
                  held ->
                      sessions
                          .reload(namespace, original.getId())
                          .flatMap(
                              fresh -> {
                                var current =
                                    new ServerWebExchangeDecorator(exchange) {
                                      @Override
                                      public Mono<WebSession> getSession() {
                                        return Mono.just(fresh);
                                      }
                                    };
                                return operation.apply(current);
                              }),
                  held -> release(key, held),
                  (held, failure) ->
                      original
                          .invalidate()
                          .then(
                              failure instanceof PlatformException known && known.status() == 401
                                  ? release(key, held)
                                  : Mono.empty()),
                  held -> original.invalidate());
            });
  }

  private Mono<String> acquire(BffNamespace namespace, String session, String key, String owner) {
    // 占用期限不短于整个 BFF 会话；进程失联或响应未知时，不能自动接管并重放旧刷新凭证。
    return Mono.defer(() -> locks.opsForValue().setIfAbsent(key, owner, BffSessions.MAX_ABSOLUTE))
        .flatMap(
            acquired ->
                Boolean.TRUE.equals(acquired)
                    ? Mono.just(owner)
                    : sessions
                        .reload(namespace, session)
                        .then(Mono.delay(Duration.ofMillis(50)))
                        .then(Mono.defer(() -> acquire(namespace, session, key, owner))));
  }

  private Mono<Void> release(String key, String owner) {
    return locks.execute(RELEASE, List.of(key), List.of(owner)).then();
  }

  static boolean credentialError(String code) {
    return Set.of("invalid_grant", "invalid_client", "invalid_scope").contains(code);
  }

  private static Throwable classify(Throwable failure) {
    if (failure instanceof PlatformException) return failure;
    if (failure instanceof OAuth2AuthorizationException oauth
        && credentialError(oauth.getError().getErrorCode())) return invalid();
    return new PlatformException("BFF_IDENTITY_UNAVAILABLE", "身份或会话存储暂不可用，请重新登录", 503);
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "当前应用登录已失效，请重新登录", 401);
  }
}
