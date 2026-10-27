package com.ken.platform.foundation.web;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class RequestContextTest {
  @Test
  void lexicalContextRestoresAfterFailureAndDoesNotExtendParentBudget() {
    var tenant = UUID.randomUUID();
    var deadline = Instant.now().plusSeconds(1);
    var parent = new CallContext("request-1", tenant, "u1", null, null, deadline);
    try (var ignored = CallContexts.bind(parent)) {
      try (var nested =
          CallContexts.bind(
              new CallContext("request-2", tenant, "u1", null, null, deadline.plusSeconds(100)))) {
        assertThat(CallContexts.current().deadline()).isEqualTo(deadline);
      }
      assertThat(CallContexts.current()).isEqualTo(parent);
      assertThatThrownBy(
              () ->
                  CallContexts.bind(
                      new CallContext("request-3", UUID.randomUUID(), "u2", null, null, null)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(CallContexts.current()).isNull();
  }

  @Test
  void reactiveFilterIgnoresForgedIdentityAndMapsStableError() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/test")
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header("X-User-Id", "forged")
                .header("X-Request-Id", "request-1"));
    var filter = new ReactiveRequestContextFilter(Clock.systemUTC(), RuntimeParameters.defaults());
    filter
        .filter(
            exchange,
            current ->
                Mono.deferContextual(
                    context -> {
                      CallContext value = context.get(CallContext.class);
                      assertThat(value.tenantId()).isNull();
                      assertThat(value.principalId()).isNull();
                      assertThat(value.requestId()).isEqualTo("request-1");
                      return Mono.error(
                          new PlatformException("DEPENDENCY_UNAVAILABLE", "服务暂不可用", 503));
                    }))
        .block();
    assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
    var json =
        JsonMapper.builder().build().readTree(exchange.getResponse().getBodyAsString().block());
    assertThat(json.get("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(json.get("request_id").asString()).isEqualTo("request-1");
  }

  @Test
  void servletFilterResetsContextAndInvalidRequestIdCannotInjectResponseHeaders() throws Exception {
    var request = new MockHttpServletRequest("GET", "/test");
    var response = new MockHttpServletResponse();
    request.addHeader("X-Request-Id", "invalid\r\nX-Other: injected");
    var filter = new ServletRequestContextFilter(Clock.systemUTC(), RuntimeParameters.defaults());
    filter.doFilter(
        request,
        response,
        (incoming, outgoing) -> {
          assertThat(CallContexts.current().principalId()).isNull();
          throw new PlatformException("DEPENDENCY_UNAVAILABLE", "服务暂不可用", 503);
        });
    assertThat(CallContexts.current()).isNull();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Request-Id")).matches("[A-Za-z0-9._-]{1,64}");
    assertThat(response.getHeader("X-Other")).isNull();
  }
}
