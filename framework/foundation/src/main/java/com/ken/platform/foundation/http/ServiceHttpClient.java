package com.ken.platform.foundation.http;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 非阻塞 HTTP；关闭底层自动重放，只允许框架额外重试一次 GET。 */
public final class ServiceHttpClient implements AutoCloseable {
  private static final String IO_STARTED = ServiceHttpClient.class.getName() + ".io";
  private final ServiceDiscovery discovery;
  private final WebClient client;
  private final Clock clock;
  private final Duration responseBudget;
  private final int getRetryExtra;
  private HttpProtection protection = HttpProtection.NONE;
  private reactor.netty.resources.ConnectionProvider pool;

  public ServiceHttpClient(
      ServiceDiscovery discovery, WebClient client, Clock clock, Duration responseBudget) {
    this(discovery, client, clock, responseBudget, 1);
  }

  public ServiceHttpClient(
      ServiceDiscovery discovery,
      WebClient client,
      Clock clock,
      Duration responseBudget,
      int getRetryExtra) {
    if (responseBudget.isNegative() || responseBudget.isZero()) {
      throw new IllegalArgumentException("调用预算必须为正数");
    }
    this.discovery = discovery;
    this.client = client;
    this.clock = clock;
    this.responseBudget = responseBudget;
    if (getRetryExtra < 0 || getRetryExtra > 1) {
      throw new IllegalArgumentException("GET 最多额外重试一次");
    }
    this.getRetryExtra = getRetryExtra;
  }

  public static ServiceHttpClient configured(
      ServiceDiscovery discovery, Clock clock, RuntimeParameters parameters) {
    return configured(discovery, clock, parameters, HttpProtection.NONE);
  }

  public static ServiceHttpClient configured(
      ServiceDiscovery discovery,
      Clock clock,
      RuntimeParameters parameters,
      HttpProtection protection) {
    var pool =
        reactor.netty.resources.ConnectionProvider.builder("platform-http")
            .maxConnections(parameters.maxConnections())
            .pendingAcquireMaxCount(parameters.maxConnections())
            .pendingAcquireTimeout(Duration.ofSeconds(1))
            .metrics(parameters.metricsEnabled())
            .build();
    var transport =
        reactor.netty.http.client.HttpClient.create(pool)
            .doOnChannelInit((observer, channel, address) -> markStarted(observer.currentContext()))
            .doOnRequest((request, connection) -> markStarted(request.currentContextView()))
            .disableRetry(true)
            .followRedirect(false)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.max(1, (int) (parameters.connectTimeoutSeconds() * 1000)));
    var webClient =
        WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport)).build();
    var result =
        new ServiceHttpClient(
            discovery,
            webClient,
            clock,
            Duration.ofMillis(Math.max(1, (long) (parameters.responseBudgetSeconds() * 1000))),
            parameters.getRetryExtra());
    result.protection = protection;
    result.pool = pool;
    return result;
  }

  public CompletableFuture<ResponseEntity<byte[]>> request(
      String service, String method, String path, byte[] body, CallContext context) {
    URI relative = URI.create(path);
    if (!path.startsWith("/")
        || path.startsWith("//")
        || relative.isAbsolute()
        || relative.getRawAuthority() != null) {
      throw new IllegalArgumentException("只允许所属服务的绝对路径");
    }
    Instant deadline = clock.instant().plus(responseBudget);
    if (context != null && context.deadline() != null && context.deadline().isBefore(deadline)) {
      deadline = context.deadline();
    }
    Duration available = Duration.between(clock.instant(), deadline);
    long monotonicDeadline =
        System.nanoTime()
            + (available.isNegative() || available.isZero() ? 0 : available.toNanos());
    Instant finalDeadline = deadline;
    return Mono.defer(
            () -> {
              if (available.isNegative() || available.isZero())
                return Mono.<ResponseEntity<byte[]>>error(timeout());
              // 发现失败不进入 Sentinel，不污染依赖失败比例。
              ServiceInstance initial = discovery.choose(service);
              return Mono.using(
                  () -> protection.enter("http.outbound/" + service),
                  lease ->
                      attempt(
                              service,
                              method.toUpperCase(java.util.Locale.ROOT),
                              path,
                              body == null ? new byte[0] : body,
                              context,
                              finalDeadline,
                              monotonicDeadline,
                              0,
                              initial)
                          .doOnCancel(
                              () ->
                                  io.micrometer.core.instrument.Metrics.counter(
                                          "platform.http.cancelled")
                                      .increment())
                          .doOnNext(
                              response -> {
                                if (java.util.Set.of(500, 502, 503, 504)
                                    .contains(response.getStatusCode().value()))
                                  lease.failed(new IllegalStateException("上游服务失败"));
                              })
                          .doOnError(
                              error -> {
                                if (!(error instanceof LocalDeadline)
                                    && error instanceof PlatformException platform
                                    && java.util.Set.of(
                                            "UPSTREAM_TIMEOUT", "DEPENDENCY_UNAVAILABLE")
                                        .contains(platform.code())) lease.failed(error);
                              }),
                  HttpProtection.Lease::close);
            })
        .toFuture();
  }

  private Mono<ResponseEntity<byte[]>> attempt(
      String service,
      String method,
      String path,
      byte[] body,
      CallContext context,
      Instant deadline,
      long monotonicDeadline,
      int retried,
      ServiceInstance first) {
    return Mono.defer(
        () -> {
          Duration wallRemaining = Duration.between(clock.instant(), deadline);
          Duration monotonicRemaining = Duration.ofNanos(monotonicDeadline - System.nanoTime());
          Duration remaining =
              wallRemaining.compareTo(monotonicRemaining) < 0 ? wallRemaining : monotonicRemaining;
          if (remaining.isZero() || remaining.isNegative()) {
            return Mono.error(timeout());
          }
          ServiceInstance instance;
          try {
            instance = first == null ? discovery.choose(service) : first;
          } catch (PlatformException failure) {
            return Mono.error(failure);
          }
          WebClient.RequestBodySpec builder =
              client
                  .method(HttpMethod.valueOf(method))
                  .uri(instance.origin().resolve(path))
                  .header("X-Request-Deadline", Long.toString(deadline.toEpochMilli()));
          if (context != null) {
            builder.header("X-Request-Id", context.requestId());
            if (context.accessToken() != null) {
              builder.header("Authorization", "Bearer " + context.accessToken());
            }
            if (context.traceparent() != null) {
              builder.header("traceparent", context.traceparent());
            }
          }
          WebClient.RequestHeadersSpec<?> operation =
              body.length == 0
                  ? builder
                  : builder.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
          var ioStarted = new java.util.concurrent.atomic.AtomicBoolean(pool == null);
          return operation
              .exchangeToMono(response -> response.toEntity(byte[].class))
              .contextWrite(values -> values.put(IO_STARTED, ioStarted))
              .timeout(
                  remaining,
                  Mono.defer(
                      () ->
                          Mono.error(
                              ioStarted.get()
                                  ? new java.util.concurrent.TimeoutException()
                                  : new LocalDeadline())))
              .onErrorResume(
                  failure -> {
                    if (poolFailure(failure)) {
                      io.micrometer.core.instrument.Metrics.counter("platform.http.pool.rejected")
                          .increment();
                      return Mono.error(
                          new PlatformException("DEPENDENCY_BUSY", "HTTP 连接池繁忙", 503));
                    }
                    if (failure instanceof java.util.concurrent.CancellationException)
                      return Mono.error(failure);
                    if (!(failure
                            instanceof
                            org.springframework.web.reactive.function.client
                                .WebClientRequestException)
                        && !(failure instanceof java.io.IOException)
                        && !isTimeout(failure)) return Mono.error(failure);
                    discovery.invalidate(service, instance);
                    if ("GET".equals(method)
                        && retried < getRetryExtra
                        && clock.instant().isBefore(deadline)
                        && !(failure instanceof java.util.concurrent.CancellationException)) {
                      return attempt(
                          service,
                          method,
                          path,
                          body,
                          context,
                          deadline,
                          monotonicDeadline,
                          retried + 1,
                          null);
                    }
                    boolean timedOut = isTimeout(failure);
                    return Mono.error(
                        timedOut
                            ? timeout()
                            : new PlatformException("DEPENDENCY_UNAVAILABLE", "服务调用失败", 503));
                  });
        });
  }

  private static boolean poolFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getClass().getSimpleName().equals("PoolAcquireTimeoutException")
          || current.getClass().getSimpleName().equals("PoolAcquirePendingLimitException"))
        return true;
    }
    return false;
  }

  private static void markStarted(reactor.util.context.ContextView context) {
    if (context.hasKey(IO_STARTED))
      context.<java.util.concurrent.atomic.AtomicBoolean>get(IO_STARTED).set(true);
  }

  private static final class LocalDeadline extends PlatformException {
    private LocalDeadline() {
      super("UPSTREAM_TIMEOUT", "等待本地连接时请求预算已耗尽", 504);
    }
  }

  @Override
  public void close() {
    if (pool != null) pool.dispose();
  }

  private static boolean isTimeout(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof io.netty.handler.timeout.TimeoutException
          || current instanceof java.util.concurrent.TimeoutException) {
        return true;
      }
    }
    return false;
  }

  private PlatformException timeout() {
    return new PlatformException("UPSTREAM_TIMEOUT", "上游请求超时；写入结果可能需要核对", 504);
  }
}
