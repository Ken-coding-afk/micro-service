package com.ken.platform.foundation.resilience;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.*;
import com.ken.platform.foundation.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import reactor.core.publisher.Sinks;

class AdmissionControlTest {
  @Test
  void servletAsyncRequestDoesNotReleaseAtReturnOrTimeoutNotification() throws Exception {
    FlowRuleManager.loadRules(
        SentinelRules.flow("[{\"resource\":\"http.inbound\",\"grade\":0,\"count\":1}]"));
    try {
      var protection = new SentinelProtection();
      var filter = new ServletAdmissionFilter(protection);
      var request = new MockHttpServletRequest("GET", "/api/v1/work");
      request.setAsyncSupported(true);
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync());
      assertThatThrownBy(() -> protection.enter("http.inbound"))
          .isInstanceOf(PlatformException.class);
      var async = (MockAsyncContext) request.getAsyncContext();
      for (var listener : async.getListeners())
        listener.onTimeout(new jakarta.servlet.AsyncEvent(async));
      assertThatThrownBy(() -> protection.enter("http.inbound"))
          .isInstanceOf(PlatformException.class);
      async.complete();
      protection.enter("http.inbound").close();
    } finally {
      FlowRuleManager.loadRules(List.of());
    }
  }

  @Test
  void localPoolTimeoutAndShortBudgetDoNotInvalidateHealthyInstance() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = Sinks.<String>one();
    var server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) -> {
                  entered.countDown();
                  return response.sendString(release.asMono());
                })
            .bindNow();
    var directory = new ServiceDiscovery(System::nanoTime);
    directory.confirm("pooled", List.of(new ServiceInstance("127.0.0.1", server.port(), "http")));
    var parameters =
        RuntimeParameters.load("log_level: INFO", "http: {max_connections: 1}", "test-service");
    try (var client = ServiceHttpClient.configured(directory, Clock.systemUTC(), parameters)) {
      var held = client.request("pooled", "GET", "/slow", null, null);
      assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var shortBudget =
          new CallContext("short", null, null, null, null, Instant.now().plusMillis(100));
      assertThatThrownBy(() -> client.request("pooled", "GET", "/short", null, shortBudget).join())
          .hasCauseInstanceOf(PlatformException.class)
          .satisfies(
              error -> assertThat(((PlatformException) error.getCause()).status()).isEqualTo(504));
      assertThat(directory.choose("pooled").port()).isEqualTo(server.port());
      assertThatThrownBy(() -> client.request("pooled", "GET", "/waiting", null, null).join())
          .hasCauseInstanceOf(PlatformException.class)
          .satisfies(
              error ->
                  assertThat(((PlatformException) error.getCause()).code())
                      .isEqualTo("DEPENDENCY_BUSY"));
      assertThat(directory.choose("pooled").port()).isEqualTo(server.port());
      release.tryEmitValue("complete");
      assertThat(held.get(3, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value())
          .isEqualTo(200);
    } finally {
      release.tryEmitValue("cleanup");
      server.disposeNow();
    }
  }
}
