package com.ken.platform.gateway.traffic;

import com.ken.platform.foundation.http.PlatformException;
import java.util.List;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/** 沿用官方令牌桶脚本与配置，只修正故障放行及 BFF routeId 分桶。 */
public final class PlatformRedisRateLimiter extends RedisRateLimiter {
  public PlatformRedisRateLimiter(
      ReactiveStringRedisTemplate redis,
      RedisScript<List<Long>> script,
      ConfigurationService configuration) {
    super(redis, script, configuration);
    super.setIncludeHeaders(true);
  }

  @Override
  public void setIncludeHeaders(boolean ignored) {
    super.setIncludeHeaders(true);
  }

  @Override
  public Mono<Response> isAllowed(String routeId, String id) {
    String canonical =
        routeId.startsWith("framework-bff-")
            ? routeId.substring("framework-bff-".length())
            : routeId;
    return super.isAllowed(canonical, id)
        .flatMap(
            result -> {
              String remaining = result.getHeaders().get(getRemainingHeader());
              if (remaining == null || !remaining.matches("[0-9]+"))
                return Mono.error(unavailable());
              if (!result.isAllowed()) {
                var config = getConfig().get(canonical);
                if (config == null) return Mono.error(unavailable());
                long retry =
                    Math.max(
                        1,
                        (long)
                            Math.ceil(
                                (double) config.getRequestedTokens() / config.getReplenishRate()));
                return Mono.error(new Rejected(retry));
              }
              return Mono.just(result);
            })
        .doOnError(
            PlatformException.class,
            error ->
                io.micrometer.core.instrument.Metrics.counter(
                        "platform.gateway.rejected", "route", canonical, "reason", error.code())
                    .increment())
        .switchIfEmpty(Mono.error(unavailable()))
        .onErrorMap(error -> error instanceof PlatformException ? error : unavailable());
  }

  private static PlatformException unavailable() {
    return new PlatformException("RATE_LIMIT_UNAVAILABLE", "限流存储暂不可用", 503);
  }

  public static final class Rejected extends PlatformException {
    private final long retryAfter;

    public Rejected(long retryAfter) {
      super("RATE_LIMITED", "请求超过当前额度", 429);
      this.retryAfter = retryAfter;
    }

    public long retryAfter() {
      return retryAfter;
    }
  }
}
