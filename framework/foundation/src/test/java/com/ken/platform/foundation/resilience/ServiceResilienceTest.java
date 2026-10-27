package com.ken.platform.foundation.resilience;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.*;
import com.ken.platform.foundation.http.*;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ServiceResilienceTest {
  @Test
  void nativeHalfOpenProbeWithoutTracedFailureClosesEvenWhenCallerCancels() {
    String resource = "http.outbound/half-open-probe";
    DegradeRuleManager.loadRules(
        SentinelRules.degrade(
            "[{\"resource\":\""
                + resource
                + "\",\"grade\":1,\"count\":0.5,\"minRequestAmount\":1,\"statIntervalMs\":10000,\"timeWindow\":1}]"));
    var protection = new SentinelProtection();
    var states = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var registry =
        com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry
            .getInstance();
    registry.addStateChangeObserver(
        "test-half-open",
        (oldState, newState, rule, snapshot) -> {
          if (rule.getResource().equals(resource)) states.add(newState.name());
        });
    try {
      try (var failed = protection.enter(resource)) {
        failed.failed(new java.io.IOException("fixture"));
      }
      assertThatThrownBy(() -> protection.enter(resource)).isInstanceOf(PlatformException.class);
      var probe = new java.util.concurrent.atomic.AtomicReference<HttpProtection.Lease>();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(4))
          .until(
              () -> {
                try {
                  probe.set(protection.enter(resource));
                  return true;
                } catch (PlatformException blocked) {
                  return false;
                }
              });
      assertThat(states).contains("OPEN", "HALF_OPEN");
      assertThatThrownBy(() -> protection.enter(resource)).isInstanceOf(PlatformException.class);
      probe.get().close();
      assertThat(states).contains("CLOSED");
      protection.enter(resource).close();
    } finally {
      registry.removeStateChangeObserver("test-half-open");
    }
  }

  @AfterEach
  void reset() {
    FlowRuleManager.loadRules(List.of());
    DegradeRuleManager.loadRules(List.of());
  }

  @Test
  void concurrentEntriesRemainOccupiedUntilActualClose() {
    FlowRuleManager.loadRules(
        SentinelRules.flow("[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":1}]"));
    var protection = new SentinelProtection();
    var held = protection.enter("http.inbound");
    assertThatThrownBy(() -> protection.enter("http.inbound"))
        .isInstanceOf(PlatformException.class)
        .satisfies(
            error -> assertThat(((PlatformException) error).code()).isEqualTo("SERVICE_BUSY"));
    held.close();
    held.close();
    protection.enter("http.inbound").close();
  }

  @Test
  void strictExceptionRatioUsesFinalHttpOutcomeAndDoesNotRetry503() {
    String resource = "http.outbound/ratio-probe";
    DegradeRuleManager.loadRules(
        SentinelRules.degrade(
            "[{\"resource\":\""
                + resource
                + "\",\"grade\":1,\"count\":0.5,\"minRequestAmount\":10,\"statIntervalMs\":100000,\"timeWindow\":20}]"));
    AtomicInteger attempts = new AtomicInteger();
    var server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) ->
                    response
                        .status(attempts.incrementAndGet() <= 5 ? 200 : 503)
                        .sendString(Mono.just("upstream-body")))
            .bindNow();
    var discovery = new ServiceDiscovery(System::nanoTime);
    discovery.confirm(
        "ratio-probe", List.of(new ServiceInstance("127.0.0.1", server.port(), "http")));
    try (var client =
        ServiceHttpClient.configured(
            discovery, Clock.systemUTC(), RuntimeParameters.defaults(), new SentinelProtection())) {
      for (int i = 1; i <= 11; i++) {
        var response = client.request("ratio-probe", "GET", "/value", null, null).join();
        assertThat(response.getStatusCode().value()).isEqualTo(i <= 5 ? 200 : 503);
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("upstream-body");
      }
      assertThat(attempts.get()).isEqualTo(11);
      assertThatThrownBy(() -> client.request("ratio-probe", "GET", "/value", null, null).join())
          .hasCauseInstanceOf(PlatformException.class)
          .satisfies(
              error ->
                  assertThat(((PlatformException) error.getCause()).code())
                      .isEqualTo("DEPENDENCY_CIRCUIT_OPEN"));
      assertThat(attempts.get()).isEqualTo(11);
      assertThat(discovery.choose("ratio-probe").port()).isEqualTo(server.port());
    } finally {
      server.disposeNow();
    }
  }

  @Test
  void sourceValidationRejectsDeletionUnknownFieldsDuplicatesAndClusterRules() {
    assertThat(SentinelRules.flow("[]")).isEmpty();
    for (String source :
        new String[] {
          "",
          "null",
          "{}",
          "[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":0}]",
          "[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":2,\"clusterMode\":true}]",
          "[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":2,\"count\":3}]",
          "[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":2,\"foreign\":0}]"
        }) {
      assertThatThrownBy(() -> SentinelRules.flow(source)).isInstanceOf(RuntimeException.class);
    }
    var properties = new SentinelRuleSource.RedactedProperties();
    properties.put("password", "SENTINEL_SECRET_CANARY");
    assertThat(properties.toString()).doesNotContain("SENTINEL_SECRET_CANARY");
  }
}
