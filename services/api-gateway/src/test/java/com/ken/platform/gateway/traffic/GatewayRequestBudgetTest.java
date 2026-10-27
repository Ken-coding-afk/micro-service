package com.ken.platform.gateway.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import com.ken.platform.foundation.http.PlatformException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayRequestBudgetTest {
  @Test
  void ordinaryBudgetCancelsWholeResponseWorkAtFifteenSeconds() {
    var cancelled = new AtomicBoolean();
    var exchange = exchange(false);
    StepVerifier.withVirtualTime(
            () ->
                new GatewayRequestBudget()
                    .filter(
                        exchange,
                        ignored -> Mono.<Void>never().doOnCancel(() -> cancelled.set(true))))
        .thenAwait(Duration.ofSeconds(15))
        .expectErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(PlatformException.class);
              assertThat(((PlatformException) error).code()).isEqualTo("UPSTREAM_TIMEOUT");
            })
        .verify();
    assertThat(cancelled).isTrue();
  }

  @Test
  void streamingRouteCanRemainActivePastOrdinaryDeadlineAndCancelsOnDisconnect() {
    var cancelled = new AtomicBoolean();
    var exchange = exchange(true);
    StepVerifier.withVirtualTime(
            () ->
                new GatewayRequestBudget()
                    .filter(
                        exchange,
                        ignored -> Mono.<Void>never().doOnCancel(() -> cancelled.set(true))))
        .expectSubscription()
        .expectNoEvent(Duration.ofSeconds(60))
        .thenCancel()
        .verify();
    assertThat(cancelled).isTrue();
  }

  private MockServerWebExchange exchange(boolean streaming) {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/items"));
    exchange
        .getAttributes()
        .put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("items")
                .uri("http://127.0.0.1:8082")
                .predicate(ignored -> true)
                .metadata("platform.streaming", streaming)
                .build());
    return exchange;
  }
}
