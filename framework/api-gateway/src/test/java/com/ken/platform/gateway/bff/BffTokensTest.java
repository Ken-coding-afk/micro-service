package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.PlatformException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 合成授权客户端只验证刷新/退出机制；真实授权码和资源协议另用身份服务 HTTP 联调。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class BffTokensTest {
  private BffRedisFixture redis;
  private BffSessions sessions;
  private BffTokens tokens;
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private final String secret = "bff-unit-client-secret-only-with-32-characters";
  private final String oldAccess = "old-access-" + UUID.randomUUID(),
      oldRefresh = "old-refresh-" + UUID.randomUUID();
  private final AtomicInteger refreshes = new AtomicInteger();
  private final List<String> revoked = new CopyOnWriteArrayList<>();
  private final WebSessionServerOAuth2AuthorizedClientRepository repository =
      new WebSessionServerOAuth2AuthorizedClientRepository();
  private ClientRegistration client;
  private OAuth2AuthenticationToken authentication;
  private BffIdentityClient identity;
  private RuntimeParameters parameters;
  private volatile String mode = "success";

  @BeforeEach
  void setup() throws Exception {
    redis = new BffRedisFixture();
    sessions = new BffSessions(redis.sessions, redis.project, "local", false, Clock.systemUTC());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/oauth2/",
        exchange -> {
          String basic =
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          ("identity-admin-tenant:" + secret).getBytes(StandardCharsets.US_ASCII));
          if (!basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          var fields = new HashMap<String, String>();
          for (String field :
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                  .split("&")) {
            String[] pair = field.split("=", 2);
            fields.put(
                URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
          }
          String response;
          int status = 200;
          if (exchange.getRequestURI().getPath().equals("/oauth2/revoke")) {
            revoked.add(digest(fields.get("token")));
            response = "{}";
          } else {
            int count = refreshes.incrementAndGet();
            if (!"refresh_token".equals(fields.get("grant_type"))
                || !oldRefresh.equals(fields.get("refresh_token"))) {
              exchange.sendResponseHeaders(400, -1);
              exchange.close();
              return;
            }
            if (mode.equals("unknown")) {
              exchange.close();
              return;
            }
            if (mode.equals("invalid")) {
              response = "{\"error\":\"invalid_grant\"}";
              status = 400;
            } else {
              try {
                Thread.sleep(100);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              response =
                  JsonMapper.builder()
                      .build()
                      .writeValueAsString(
                          Map.of(
                              "access_token",
                              "fresh-access-" + count,
                              "refresh_token",
                              "fresh-refresh-" + count,
                              "expires_in",
                              600,
                              "token_type",
                              "Bearer",
                              "scope",
                              "profile"));
            }
          }
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var discovery = new ServiceDiscovery(System::nanoTime);
    discovery.confirm(
        "identity-service",
        List.of(new ServiceInstance("127.0.0.1", server.getAddress().getPort(), "http")));
    parameters = new RuntimeParameters("INFO", 1, 3, 1, true);
    identity = new BffIdentityClient(discovery, parameters, Clock.systemUTC());
    client =
        ClientRegistration.withRegistrationId("tenant")
            .clientId("identity-admin-tenant")
            .clientSecret(secret)
            .clientAuthenticationMethod(
                org.springframework.security.oauth2.core.ClientAuthenticationMethod
                    .CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/bff/identity/tenant/callback")
            .authorizationUri("http://localhost/oauth2/authorize")
            .tokenUri("http://identity-service/oauth2/token")
            .scope("profile")
            .build();
    var user =
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_BFF_USER")),
            Map.of("sub", UUID.randomUUID().toString()),
            "sub");
    authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "tenant");
    tokens = service(sessions);
  }

  private BffTokens service(BffSessions manager) {
    return new BffTokens(
        manager,
        repository,
        redis.strings,
        new InMemoryReactiveClientRegistrationRepository(client),
        identity,
        parameters,
        Clock.systemUTC(),
        redis.project,
        "local");
  }

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
    if (executor != null) executor.close();
    if (redis != null) redis.close();
  }

  private ServerWebExchange exchange(String id, BffSessions manager) {
    var request = MockServerHttpRequest.get("http://localhost/bff/identity/tenant/api/me");
    if (id != null) request.cookie(new HttpCookie(manager.cookieName(BffNamespace.TENANT), id));
    var mock = MockServerWebExchange.from(request);
    WebSession session = manager.getSession(mock).block();
    return new ServerWebExchangeDecorator(mock) {
      @Override
      public Mono<WebSession> getSession() {
        return Mono.just(session);
      }
    };
  }

  private String create(boolean expired) {
    var exchange = exchange(null, sessions);
    var authorized =
        new OAuth2AuthorizedClient(
            client,
            authentication.getName(),
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                oldAccess,
                Instant.now().minusSeconds(600),
                Instant.now().plusSeconds(expired ? -1 : 600),
                Set.of("profile")),
            new OAuth2RefreshToken(oldRefresh, Instant.now().minusSeconds(600)));
    repository.saveAuthorizedClient(authorized, authentication, exchange).block();
    exchange.getResponse().setComplete().block();
    return exchange.getSession().block().getId();
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void unexpiredAccessTokenDoesNotTriggerRefreshOrChangeExpiry() {
    String id = create(false);
    var value = tokens.current(exchange(id, sessions), authentication).block();
    assertThat(value).isNotNull();
    assertThat(digest(value.getAccessToken().getTokenValue())).isEqualTo(digest(oldAccess));
    assertThat(refreshes.get()).isZero();
  }

  @Test
  void twoInstancesRefreshOnceAndReloadLatestRotationBeforeUsingOldSnapshot() {
    String id = create(true);
    var secondManager =
        new BffSessions(redis.sessions, redis.project, "local", false, Clock.systemUTC());
    var first = exchange(id, sessions);
    var second = exchange(id, secondManager);
    var values =
        Mono.zip(
                tokens.current(first, authentication),
                service(secondManager).current(second, authentication))
            .block(Duration.ofSeconds(10));
    assertThat(values).isNotNull();
    assertThat(refreshes.get()).isEqualTo(1);
    assertThat(digest(values.getT1().getAccessToken().getTokenValue()))
        .isEqualTo(digest("fresh-access-1"));
    assertThat(digest(values.getT2().getRefreshToken().getTokenValue()))
        .isEqualTo(digest("fresh-refresh-1"));
    first.getResponse().setComplete().block();
    second.getResponse().setComplete().block();
    var stored =
        repository
            .<OAuth2AuthorizedClient>loadAuthorizedClient(
                "tenant", authentication, exchange(id, sessions))
            .block();
    assertThat(digest(stored.getRefreshToken().getTokenValue()))
        .isEqualTo(digest("fresh-refresh-1"));
    assertThat(
            redis
                .strings
                .keys(redis.project + ":local:api-gateway:global:bff-operation:*")
                .collectList()
                .block())
        .isEmpty();
  }

  @Test
  void unknownRefreshOutcomeInvalidatesLocalSessionAndCannotReplayOldRefreshToken() {
    mode = "unknown";
    String id = create(true);
    assertThatThrownBy(
            () ->
                tokens.current(exchange(id, sessions), authentication).block(Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    assertThatThrownBy(() -> tokens.current(exchange(id, sessions), authentication).block())
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));
    assertThat(refreshes.get()).isEqualTo(1);
    var keys =
        redis
            .strings
            .keys(redis.project + ":local:api-gateway:global:bff-operation:*")
            .collectList()
            .block();
    assertThat(keys).hasSize(1);
    assertThat(redis.strings.getExpire(keys.getFirst()).block().getSeconds())
        .isBetween(28000L, 28800L);
  }

  @Test
  void definiteInvalidGrantInvalidatesSessionWithoutCreatingRetryLoop() {
    mode = "invalid";
    String id = create(true);
    assertThatThrownBy(() -> tokens.current(exchange(id, sessions), authentication).block())
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));
    assertThat(refreshes.get()).isEqualTo(1);
    assertThat(
            redis
                .strings
                .keys(redis.project + ":local:api-gateway:global:bff-operation:*")
                .collectList()
                .block())
        .isEmpty();
  }

  @Test
  void logoutRevokesLatestAccessAndRefreshAndRemovesOnlyItsOwnPersistentSession() {
    String id = create(true);
    tokens.current(exchange(id, sessions), authentication).block();
    tokens.logout(exchange(id, sessions), authentication).block();
    assertThat(revoked).containsExactly(digest("fresh-refresh-1"), digest("fresh-access-1"));
    assertThat(refreshes.get()).isEqualTo(1);
    assertThat(
            repository
                .loadAuthorizedClient("tenant", authentication, exchange(id, sessions))
                .block())
        .isNull();
  }

  @Test
  void existingUnknownOperationIsNotStolenOrTurnedIntoAnotherRefresh() {
    String id = create(true);
    String key = redis.project + ":local:api-gateway:global:bff-operation:TENANT:" + id;
    redis
        .strings
        .opsForValue()
        .set(key, "unknown-prior-operation", BffSessions.MAX_ABSOLUTE)
        .block();
    var bounded =
        new BffTokens(
            sessions,
            repository,
            redis.strings,
            new InMemoryReactiveClientRegistrationRepository(client),
            identity,
            new RuntimeParameters("INFO", 1, 0.1, 0, true),
            Clock.systemUTC(),
            redis.project,
            "local");
    assertThatThrownBy(
            () ->
                bounded
                    .current(exchange(id, sessions), authentication)
                    .block(Duration.ofSeconds(3)))
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
    assertThat(refreshes.get()).isZero();
    assertThat(redis.strings.opsForValue().get(key).block()).isEqualTo("unknown-prior-operation");
  }
}
