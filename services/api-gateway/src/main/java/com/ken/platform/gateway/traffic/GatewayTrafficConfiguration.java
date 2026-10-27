package com.ken.platform.gateway.traffic;

import com.ken.platform.foundation.http.PlatformException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class GatewayTrafficConfiguration {
  @Bean
  org.springframework.cloud.gateway.config.HttpClientCustomizer platformGatewayHttpClient() {
    // SCG response-timeout 只约束响应建立；Netty responseTimeout 继续约束响应体读空闲。
    // Reactor Netty 默认还会重放一次连接重置请求，网关必须显式关闭。
    return client -> client.responseTimeout(java.time.Duration.ofSeconds(45)).disableRetry(true);
  }

  @Bean
  PlatformRedisRateLimiter platformRedisRateLimiter(
      @Qualifier("bffOperationRedis") ReactiveStringRedisTemplate redis,
      @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
      ConfigurationService configuration) {
    return new PlatformRedisRateLimiter(redis, script, configuration);
  }

  @Bean
  KeyResolver tenantOrClientKeyResolver(Environment environment) {
    String project = environment.getRequiredProperty("PROJECT_CODE");
    String appEnv = environment.getRequiredProperty("APP_ENV");
    return exchange ->
        Mono.fromSupplier(
            () -> {
              RateSubject subject = exchange.getAttribute(RateSubject.ATTRIBUTE);
              Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
              if (subject == null || route == null)
                throw new PlatformException("INVALID_CREDENTIALS", "缺少可信限流主体", 401);
              return subject.key(
                  project,
                  appEnv,
                  (String)
                      route.getMetadata().getOrDefault("platform.logical-route", route.getId()));
            });
  }

  @Bean
  GatewayRequestBudget platformGatewayBudget(io.micrometer.core.instrument.MeterRegistry metrics) {
    return new GatewayRequestBudget(metrics);
  }
}
