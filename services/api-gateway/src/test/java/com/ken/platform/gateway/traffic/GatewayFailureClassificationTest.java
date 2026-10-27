package com.ken.platform.gateway.traffic;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.gateway.config.FailedInstanceFilter;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayFailureClassificationTest {
  @Test
  void onlyTransportFailureEvictsSelectedInstance() {
    var discovery = new ServiceDiscovery(System::nanoTime);
    var instance = new ServiceInstance("127.0.0.1", 18082, "http");
    discovery.confirm("analysis", List.of(instance));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/items"));
    exchange
        .getAttributes()
        .put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async().id("items").uri("lb://analysis").predicate(ignored -> true).build());
    exchange
        .getAttributes()
        .put(
            ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
            URI.create("http://127.0.0.1:18082/api/v1/items"));
    var filter = new FailedInstanceFilter(discovery);
    for (var failure :
        List.of(
            new PlatformException("DEPENDENCY_BUSY", "本地池满", 503),
            new java.util.concurrent.CancellationException())) {
      StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(failure)))
          .expectErrorMatches(error -> error == failure)
          .verify();
      assertThat(discovery.choose("analysis")).isEqualTo(instance);
    }
    StepVerifier.create(
            filter.filter(
                exchange, ignored -> Mono.error(new java.net.ConnectException("fixture"))))
        .expectError(java.net.ConnectException.class)
        .verify();
    assertThatThrownBy(() -> discovery.choose("analysis")).isInstanceOf(PlatformException.class);
  }
}
