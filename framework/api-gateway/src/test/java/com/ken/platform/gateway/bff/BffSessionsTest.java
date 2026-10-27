package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import tools.jackson.databind.json.JsonMapper;

/** 实际 Netty/Spring Session/Redis 存储；测试处理器仅用于验证会话，不充当产品 BFF 路由。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class BffSessionsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final String project = "bff-test-" + UUID.randomUUID();
  private final String privateValue = "server-only-" + UUID.randomUUID();
  private final MutableClock clock = new MutableClock();
  private final HttpClient http = HttpClient.newHttpClient();
  private LettuceConnectionFactory connection;
  private ReactiveRedisTemplate<String, Object> redis;
  private BffSessions sessions;
  private DisposableServer server;
  private String origin;

  private static final class MutableClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    var binding =
        JSON.readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_REDIS_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("app_env").asString()).isEqualTo("local");
    var config =
        new RedisStandaloneConfiguration(
            binding.get("host").asString(), binding.get("port").asInt());
    config.setPassword(
        RedisPassword.of(
            Files.readString(Path.of(binding.get("password_file").asString())).strip()));
    connection =
        new LettuceConnectionFactory(
            config,
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    connection.afterPropertiesSet();
    connection.start();
    var strings = new StringRedisSerializer();
    var context =
        RedisSerializationContext.<String, Object>newSerializationContext(strings)
            .value(BffSessions.serializer())
            .hashKey(strings)
            .hashValue(BffSessions.serializer())
            .build();
    redis = new ReactiveRedisTemplate<>(connection, context);
    sessions = new BffSessions(redis, project, "local", true, clock);
    start(sessions);
  }

  private void start(BffSessions manager) {
    var handler =
        WebHttpHandlerBuilder.webHandler(
                exchange ->
                    exchange
                        .getSession()
                        .flatMap(
                            session -> {
                              String path = exchange.getRequest().getPath().value();
                              Mono<Void> action = Mono.empty();
                              if (path.endsWith("/create")) {
                                session.getAttributes().put("private", privateValue);
                                session
                                    .getAttributes()
                                    .put(
                                        "csrf",
                                        new DefaultCsrfToken(
                                            "X-CSRF-TOKEN", "_csrf", privateValue + "-csrf"));
                              }
                              if (path.endsWith("/oauth")) {
                                var registration =
                                    ClientRegistration.withRegistrationId("tenant")
                                        .clientId("identity-admin-tenant")
                                        .clientSecret(privateValue)
                                        .authorizationGrantType(
                                            AuthorizationGrantType.AUTHORIZATION_CODE)
                                        .redirectUri(
                                            "https://public.example/bff/identity/tenant/callback")
                                        .authorizationUri("https://public.example/oauth2/authorize")
                                        .tokenUri("https://internal.example/oauth2/token")
                                        .userInfoUri("https://internal.example/api/v1/identity/me")
                                        .userNameAttributeName("sub")
                                        .scope("profile")
                                        .build();
                                var authorization =
                                    OAuth2AuthorizationRequest.authorizationCode()
                                        .authorizationUri("https://public.example/oauth2/authorize")
                                        .clientId("identity-admin-tenant")
                                        .redirectUri(registration.getRedirectUri())
                                        .scopes(Set.of("profile"))
                                        .state(privateValue + "-state")
                                        .attributes(
                                            Map.of("code_verifier", privateValue + "-verifier"))
                                        .build();
                                var principal =
                                    new DefaultOAuth2User(
                                        List.of(new SimpleGrantedAuthority("ROLE_USER")),
                                        Map.of("sub", "test-user"),
                                        "sub");
                                session.getAttributes().put("authorization", authorization);
                                session
                                    .getAttributes()
                                    .put(
                                        "security",
                                        new SecurityContextImpl(
                                            new OAuth2AuthenticationToken(
                                                principal, principal.getAuthorities(), "tenant")));
                                session
                                    .getAttributes()
                                    .put(
                                        "client",
                                        new OAuth2AuthorizedClient(
                                            registration,
                                            "test-user",
                                            new OAuth2AccessToken(
                                                OAuth2AccessToken.TokenType.BEARER,
                                                privateValue + "-access",
                                                Instant.now(),
                                                Instant.now().plusSeconds(600),
                                                Set.of("profile")),
                                            new OAuth2RefreshToken(
                                                privateValue + "-refresh", Instant.now())));
                              }
                              if (path.endsWith("/rotate")) action = session.changeSessionId();
                              if (path.endsWith("/corrupt"))
                                session.getAttributes().put(BffSessions.NAMESPACE, "OTHER");
                              if (path.endsWith("/logout")) action = session.invalidate();
                              if (path.endsWith("/fail-save")) {
                                session.getAttributes().put("pending-write", privateValue);
                                action =
                                    Mono.fromRunnable(connection::stop)
                                        .subscribeOn(
                                            reactor.core.scheduler.Schedulers.boundedElastic())
                                        .then();
                              }
                              return action.then(
                                  Mono.defer(
                                      () -> {
                                        var csrf = session.<DefaultCsrfToken>getAttribute("csrf");
                                        var client =
                                            session.<OAuth2AuthorizedClient>getAttribute("client");
                                        var request =
                                            session.<OAuth2AuthorizationRequest>getAttribute(
                                                "authorization");
                                        var security =
                                            session.<SecurityContextImpl>getAttribute("security");
                                        boolean oauth =
                                            client != null
                                                && client
                                                    .getAccessToken()
                                                    .getTokenValue()
                                                    .equals(privateValue + "-access")
                                                && client
                                                    .getRefreshToken()
                                                    .getTokenValue()
                                                    .equals(privateValue + "-refresh")
                                                && request != null
                                                && (privateValue + "-verifier")
                                                    .equals(request.getAttribute("code_verifier"))
                                                && security != null
                                                && security
                                                    .getAuthentication()
                                                    .getName()
                                                    .equals("test-user");
                                        byte[] body =
                                            JSON.writeValueAsBytes(
                                                Map.of(
                                                    "stored",
                                                    privateValue.equals(
                                                        session.getAttribute("private")),
                                                    "csrf",
                                                    csrf != null
                                                        && csrf.getToken()
                                                            .equals(privateValue + "-csrf"),
                                                    "oauth",
                                                    oauth));
                                        return exchange
                                            .getResponse()
                                            .writeWith(
                                                Mono.just(
                                                    exchange
                                                        .getResponse()
                                                        .bufferFactory()
                                                        .wrap(body)));
                                      }));
                            }))
            .sessionManager(manager)
            .exceptionHandler(
                (exchange, error) -> {
                  exchange
                      .getResponse()
                      .setStatusCode(
                          HttpStatusCode.valueOf(
                              error instanceof PlatformException known ? known.status() : 500));
                  byte[] body =
                      JSON.writeValueAsBytes(
                          Map.of(
                              "code",
                              error instanceof PlatformException known ? known.code() : "ERROR"));
                  return exchange
                      .getResponse()
                      .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                })
            .build();
    server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow();
    origin = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void close() {
    if (server != null) server.disposeNow();
    if (connection != null) {
      try {
        if (!connection.isRunning()) connection.start();
        var cleanup = new StringRedisTemplate(connection);
        var keys = cleanup.keys(project + ":*");
        if (keys != null && !keys.isEmpty()) {
          assertThat(keys).allMatch(key -> key.startsWith(project + ":"));
          cleanup.delete(keys);
        }
      } finally {
        connection.destroy();
      }
    }
  }

  private HttpResponse<String> call(BffNamespace namespace, String action, String cookie)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(origin + namespace.path() + "/" + action)).GET();
    if (cookie != null) request.header("Cookie", cookie);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String cookie(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private boolean stored(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain(privateValue);
    return JSON.readTree(response.body()).get("stored").asBoolean();
  }

  @Test
  void threeNamespacesUseDifferentCookiesRepositoriesAndCannotReplayOtherNamespaceId()
      throws Exception {
    var cookies = new java.util.EnumMap<BffNamespace, String>(BffNamespace.class);
    for (var namespace : BffNamespace.values()) {
      String value = cookie(call(namespace, "create", null));
      cookies.put(namespace, value);
      assertThat(stored(call(namespace, "read", value))).isTrue();
    }
    assertThat(cookies.values().stream().map(value -> value.split("=", 2)[0]).distinct())
        .hasSize(3);
    for (var source : BffNamespace.values())
      for (var target : BffNamespace.values())
        if (source != target) {
          assertThat(stored(call(target, "read", cookies.get(source)))).isFalse();
          String copiedId =
              sessions.cookieName(target) + "=" + cookies.get(source).split("=", 2)[1];
          assertThat(stored(call(target, "read", copiedId))).isFalse();
        }
  }

  @Test
  void cookiesAreSecureHttpOnlyLaxAndBoundToExactNamespacePath() throws Exception {
    for (var namespace : BffNamespace.values()) {
      var response = call(namespace, "create", null);
      String header = response.headers().firstValue("Set-Cookie").orElseThrow();
      var parsed = java.net.HttpCookie.parse(header).getFirst();
      assertThat(parsed.getPath()).isEqualTo(namespace.path());
      assertThat(parsed.getSecure()).isTrue();
      assertThat(parsed.isHttpOnly()).isTrue();
      assertThat(header.toLowerCase(java.util.Locale.ROOT)).contains("samesite=lax");
    }
    assertThatThrownBy(() -> new BffSessions(redis, project, "prod", false, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(BffNamespace.forPath("/bff/identity/platform-extra/api/me")).isNull();
    assertThat(BffNamespace.forPath("/api/v1/identity/me")).isNull();
    assertThat(BffNamespace.forPath("/bff/identity/tenant")).isEqualTo(BffNamespace.TENANT);
  }

  @Test
  void csrfAndOfficialOAuthObjectsSurviveRealRedisRoundTripWithoutBrowserToken() throws Exception {
    String value = cookie(call(BffNamespace.TENANT, "create", null));
    assertThat(call(BffNamespace.TENANT, "oauth", value).statusCode()).isEqualTo(200);
    var response = call(BffNamespace.TENANT, "read", value);
    assertThat(stored(response)).isTrue();
    assertThat(JSON.readTree(response.body()).get("csrf").asBoolean()).isTrue();
    assertThat(JSON.readTree(response.body()).get("oauth").asBoolean()).isTrue();
  }

  @Test
  void idRotationAndLogoutInvalidateOnlyTheirOwnNamespace() throws Exception {
    String platform = cookie(call(BffNamespace.PLATFORM, "create", null));
    String tenant = cookie(call(BffNamespace.TENANT, "create", null));
    String rotated = cookie(call(BffNamespace.PLATFORM, "rotate", platform));
    assertThat(rotated.equals(platform)).isFalse();
    assertThat(stored(call(BffNamespace.PLATFORM, "read", platform))).isFalse();
    assertThat(stored(call(BffNamespace.PLATFORM, "read", rotated))).isTrue();
    assertThat(stored(call(BffNamespace.TENANT, "read", tenant))).isTrue();
    assertThat(call(BffNamespace.PLATFORM, "logout", rotated).statusCode()).isEqualTo(200);
    assertThat(stored(call(BffNamespace.PLATFORM, "read", rotated))).isFalse();
    assertThat(stored(call(BffNamespace.TENANT, "read", tenant))).isTrue();
  }

  @Test
  void idleRedisTtlCannotExtendEightHourAbsoluteDeadline() throws Exception {
    Instant created = clock.instant();
    String value = cookie(call(BffNamespace.PLATFORM, "create", null));
    var key = project + ":local:api-gateway:global:bff:platform:sessions:" + value.split("=", 2)[1];
    Long ttl = redis.getExpire(key).map(Duration::getSeconds).block();
    assertThat(ttl).isBetween(1L, 1800L);
    clock.now.set(created.plus(Duration.ofHours(8)).minusMillis(1));
    assertThat(stored(call(BffNamespace.PLATFORM, "read", value))).isTrue();
    clock.now.set(created.plus(Duration.ofHours(8)));
    assertThat(stored(call(BffNamespace.PLATFORM, "read", value))).isFalse();
  }

  @Test
  void malformedOrRepeatedCookieAndCorruptedNamespaceDoNotExposeStoredState() throws Exception {
    String value = cookie(call(BffNamespace.PLATFORM, "create", null));
    assertThat(call(BffNamespace.PLATFORM, "read", value + "; " + value).statusCode())
        .isEqualTo(400);
    assertThat(
            call(
                    BffNamespace.PLATFORM,
                    "read",
                    sessions.cookieName(BffNamespace.PLATFORM) + "=invalid")
                .statusCode())
        .isEqualTo(400);
    assertThat(call(BffNamespace.PLATFORM, "corrupt", value).statusCode()).isEqualTo(200);
    assertThat(stored(call(BffNamespace.PLATFORM, "read", value))).isFalse();
  }

  @Test
  void redisClientFailureReturns503WithoutAnonymousFallbackOrCredentialEcho() throws Exception {
    String value = cookie(call(BffNamespace.PLATFORM, "create", null));
    connection.stop();
    try {
      var response = call(BffNamespace.PLATFORM, "read", value);
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(JSON.readTree(response.body()).get("code").asString())
          .isEqualTo("BFF_SESSION_UNAVAILABLE");
      assertThat(response.body()).doesNotContain(privateValue, "password", "RedisConnection");
    } finally {
      connection.start();
    }
  }

  @Test
  void redisSaveFailureBeforeResponseCommitReturns503AndCannotReportSuccess() throws Exception {
    String value = cookie(call(BffNamespace.PLATFORM, "create", null));
    try {
      var response = call(BffNamespace.PLATFORM, "fail-save", value);
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(JSON.readTree(response.body()).get("code").asString())
          .isEqualTo("BFF_SESSION_UNAVAILABLE");
      assertThat(response.body()).doesNotContain(privateValue, "RedisConnection");
    } finally {
      connection.start();
    }
  }
}
