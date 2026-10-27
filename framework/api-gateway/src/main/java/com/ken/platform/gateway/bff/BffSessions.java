package com.ken.platform.gateway.bff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.session.web.server.session.SpringSessionWebSessionStore;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.WebSessionManager;
import org.springframework.web.server.session.WebSessionStore;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** 三套官方 Spring Session Redis 仓库与 Cookie；绝对期限独立于空闲续期。 */
public final class BffSessions implements WebSessionManager {
  static final Duration MAX_ABSOLUTE = Duration.ofHours(8);
  static final Duration MAX_IDLE = Duration.ofMinutes(30);
  static final String NAMESPACE = BffSessions.class.getName() + ".namespace";
  static final String EXPIRES = BffSessions.class.getName() + ".expires";
  private final Map<BffNamespace, DefaultWebSessionManager> managers;
  private final Map<BffNamespace, WebSessionStore> stores;
  private final String cookiePrefix;
  private final Clock clock;

  public BffSessions(
      ReactiveRedisOperations<String, Object> redis,
      String project,
      String environment,
      boolean secure,
      Clock clock) {
    if (project == null
        || !project.matches("[a-z][a-z0-9-]{0,63}")
        || !Set.of("local", "test", "prod").contains(environment == null ? "" : environment)
        || !secure && !environment.equals("local"))
      throw new IllegalArgumentException("BFF 会话缺少有效项目/环境或安全传输绑定");
    this.clock = clock;
    cookiePrefix = project + "_" + environment + "_BFF_";
    var created = new EnumMap<BffNamespace, DefaultWebSessionManager>(BffNamespace.class);
    var createdStores = new EnumMap<BffNamespace, WebSessionStore>(BffNamespace.class);
    for (var namespace : BffNamespace.values()) {
      var repository = new ReactiveRedisSessionRepository(redis);
      repository.setRedisKeyNamespace(
          project
              + ":"
              + environment
              + ":api-gateway:global:bff:"
              + namespace.name().toLowerCase(java.util.Locale.ROOT));
      repository.setDefaultMaxInactiveInterval(MAX_IDLE);
      var store = new SpringSessionWebSessionStore<>(repository);
      store.setClock(clock);
      var cookie = new CookieWebSessionIdResolver();
      cookie.setCookieName(cookieName(namespace));
      cookie.addCookieInitializer(
          builder -> builder.path(namespace.path()).httpOnly(true).secure(secure).sameSite("Lax"));
      var manager = new DefaultWebSessionManager();
      var guarded = new GuardedStore(store);
      manager.setSessionStore(guarded);
      manager.setSessionIdResolver(cookie);
      created.put(namespace, manager);
      createdStores.put(namespace, guarded);
    }
    managers = Map.copyOf(created);
    stores = Map.copyOf(createdStores);
  }

  public String cookieName(BffNamespace namespace) {
    return cookiePrefix + namespace.name();
  }

  /** 串行刷新/退出先从持久会话重读，不能复用请求开始时的旧 refresh token。 */
  Mono<WebSession> reload(BffNamespace namespace, String id) {
    return stores
        .get(namespace)
        .retrieveSession(id)
        .flatMap(
            session -> {
              Object expiry = session.getAttribute(EXPIRES);
              if (!namespace.name().equals(session.getAttribute(NAMESPACE))
                  || !(expiry instanceof Long)
                  || clock.millis() >= (Long) expiry)
                return session
                    .invalidate()
                    .then(
                        Mono.error(new PlatformException("INVALID_CREDENTIALS", "浏览器会话已过期", 401)));
              return Mono.just(session);
            })
        .switchIfEmpty(Mono.error(new PlatformException("INVALID_CREDENTIALS", "浏览器会话已过期", 401)));
  }

  @Override
  public Mono<WebSession> getSession(ServerWebExchange exchange) {
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    if (namespace == null) return Mono.error(new IllegalStateException("非 BFF 入口不得读取浏览器会话"));
    return Mono.defer(
            () -> {
              var cookies = exchange.getRequest().getCookies().get(cookieName(namespace));
              if (cookies != null
                  && (cookies.size() != 1
                      || !cookies
                          .getFirst()
                          .getValue()
                          .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                return Mono.error(
                    new PlatformException("INVALID_REQUEST", "浏览器会话 Cookie 不合法", 400));
              var manager = managers.get(namespace);
              return manager
                  .getSession(exchange)
                  .flatMap(
                      session -> {
                        Object bound = session.getAttribute(NAMESPACE);
                        Object expires = session.getAttribute(EXPIRES);
                        if (bound != null
                            && (!namespace.name().equals(bound)
                                || !(expires instanceof Long)
                                || clock.millis() >= (Long) expires)) {
                          return session
                              .invalidate()
                              .then(manager.getSession(exchange))
                              .map(fresh -> initialize(fresh, namespace));
                        }
                        return Mono.just(initialize(session, namespace));
                      });
            })
        .onErrorMap(BffSessions::unavailable);
  }

  private WebSession initialize(WebSession session, BffNamespace namespace) {
    if (session.getAttribute(NAMESPACE) == null) {
      session.getAttributes().put(NAMESPACE, namespace.name());
      session.getAttributes().put(EXPIRES, clock.millis() + MAX_ABSOLUTE.toMillis());
    }
    return session;
  }

  private static Throwable unavailable(Throwable error) {
    return error instanceof PlatformException
        ? error
        : new PlatformException("BFF_SESSION_UNAVAILABLE", "浏览器会话存储暂不可用", 503);
  }

  /** DefaultWebSessionManager 的响应提交回调也必须经过同一错误合同。 */
  private record GuardedStore(WebSessionStore delegate) implements WebSessionStore {
    @Override
    public Mono<WebSession> createWebSession() {
      return Mono.defer(delegate::createWebSession)
          .map(session -> (WebSession) new GuardedSession(session))
          .onErrorMap(BffSessions::unavailable);
    }

    @Override
    public Mono<WebSession> retrieveSession(String id) {
      return Mono.defer(() -> delegate.retrieveSession(id))
          .map(session -> (WebSession) new GuardedSession(session))
          .onErrorMap(BffSessions::unavailable);
    }

    @Override
    public Mono<Void> removeSession(String id) {
      return Mono.defer(() -> delegate.removeSession(id)).onErrorMap(BffSessions::unavailable);
    }

    @Override
    public Mono<WebSession> updateLastAccessTime(WebSession session) {
      var actual = session instanceof GuardedSession guarded ? guarded.delegate() : session;
      return Mono.defer(() -> delegate.updateLastAccessTime(actual))
          .map(updated -> (WebSession) new GuardedSession(updated))
          .onErrorMap(BffSessions::unavailable);
    }
  }

  private record GuardedSession(WebSession delegate) implements WebSession {
    @Override
    public String getId() {
      return delegate.getId();
    }

    @Override
    public Map<String, Object> getAttributes() {
      return delegate.getAttributes();
    }

    @Override
    public void start() {
      delegate.start();
    }

    @Override
    public boolean isStarted() {
      return delegate.isStarted();
    }

    @Override
    public boolean isExpired() {
      return delegate.isExpired();
    }

    @Override
    public Instant getCreationTime() {
      return delegate.getCreationTime();
    }

    @Override
    public Instant getLastAccessTime() {
      return delegate.getLastAccessTime();
    }

    @Override
    public void setMaxIdleTime(Duration duration) {
      delegate.setMaxIdleTime(duration);
    }

    @Override
    public Duration getMaxIdleTime() {
      return delegate.getMaxIdleTime();
    }

    @Override
    public Mono<Void> save() {
      return Mono.defer(delegate::save).onErrorMap(BffSessions::unavailable);
    }

    @Override
    public Mono<Void> changeSessionId() {
      return Mono.defer(delegate::changeSessionId).onErrorMap(BffSessions::unavailable);
    }

    @Override
    public Mono<Void> invalidate() {
      return Mono.defer(delegate::invalidate).onErrorMap(BffSessions::unavailable);
    }

    @Override
    public String toString() {
      return "BffSession[REDACTED]";
    }
  }

  public static JacksonJsonRedisSerializer<Object> serializer() {
    var validator =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(Long.class)
            .allowIfSubType(Integer.class)
            .allowIfSubType(DefaultCsrfToken.class);
    var mapper =
        JsonMapper.builder()
            .addModules(
                SecurityJacksonModules.getModules(BffSessions.class.getClassLoader(), validator))
            .addMixIn(DefaultCsrfToken.class, CsrfMixin.class)
            .build();
    return new JacksonJsonRedisSerializer<>(mapper, Object.class);
  }

  private abstract static class CsrfMixin {
    @JsonCreator
    CsrfMixin(
        @JsonProperty("headerName") String header,
        @JsonProperty("parameterName") String parameter,
        @JsonProperty("token") String token) {}
  }
}
