package com.ken.platform.foundation.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.PlatformException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorsTest {
  @Test
  void safeDiagnosticPreservesLocationWithoutExceptionMessage() {
    var diagnostic = ApiErrors.diagnostic(new IllegalStateException("password=private"));
    assertThat(diagnostic)
        .contains(
            "IllegalStateException",
            "safeDiagnosticPreservesLocationWithoutExceptionMessage",
            "ApiErrorsTest.java:")
        .doesNotContain("password", "private");
  }

  @RestController
  static class Controller {
    @GetMapping("/api/validation")
    Object validation(@RequestParam int value) {
      return Map.of("value", value);
    }

    @PostMapping("/api/body")
    Object body(@RequestBody Map<String, Object> body) {
      return body;
    }

    @GetMapping("/api/failure")
    Object failure() {
      throw new IllegalStateException("password=private");
    }

    @GetMapping("/api/busy")
    Object busy() {
      throw new PlatformException("SERVICE_BUSY", "忙碌", 429);
    }

    @GetMapping("/api/timeout")
    Object timeout() {
      throw new PlatformException("UPSTREAM_TIMEOUT", "超时", 504);
    }
  }

  @Test
  void realMvcUsesSharedErrorFixtures() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new Controller())
            .setHandlerExceptionResolvers(new ServletApiErrors())
            .addFilters(
                new ServletRequestContextFilter(Clock.systemUTC(), RuntimeParameters.defaults()))
            .build();
    var json = JsonMapper.builder().build();
    var fixtures =
        json.readTree(Files.readString(Path.of("../../tests/fixtures/api-errors-v1.json")));
    for (var value : fixtures.get("cases")) {
      var request =
          request(
                  org.springframework.http.HttpMethod.valueOf(value.get("method").asString()),
                  value.get("path").asString())
              .header("X-Request-Id", "fixture-request")
              .contentType("application/json");
      if (value.has("body")) request.content(value.get("body").asString());
      var response = mvc.perform(request).andReturn().getResponse();
      assertThat(response.getStatus())
          .as(value.get("name").asString())
          .isEqualTo(value.get("status").asInt());
      var body = json.readTree(response.getContentAsByteArray());
      assertThat(body.size()).isEqualTo(4);
      assertThat(body.get("code").asString()).isEqualTo(value.get("code").asString());
      assertThat(body.get("data").isNull()).isTrue();
      assertThat(body.get("request_id").asString())
          .isEqualTo(response.getHeader("X-Request-Id"))
          .isEqualTo("fixture-request");
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(response.getContentAsString()).doesNotContain("private");
      if (value.get("name").asString().equals("method"))
        assertThat(response.getHeader("Allow")).contains("GET");
    }
  }

  @Test
  void reactiveErrorsAndProtocolBoundary() {
    var filter = new ReactiveRequestContextFilter(Clock.systemUTC(), RuntimeParameters.defaults());
    var json = JsonMapper.builder().build();
    for (var failure :
        new Exception[] {
          new ResponseStatusException(HttpStatus.NOT_FOUND, "private"),
          new IllegalStateException("private")
        }) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/bff/web/missing").header("X-Request-Id", "reactive-id"));
      filter.filter(exchange, ignored -> Mono.error(failure)).block();
      var body = json.readTree(exchange.getResponse().getBodyAsString().block());
      assertThat(body.get("code").asString())
          .isEqualTo(failure instanceof ResponseStatusException ? "NOT_FOUND" : "INTERNAL_ERROR");
      assertThat(body.get("request_id").asString()).isEqualTo("reactive-id");
      assertThat(body.toString()).doesNotContain("private");
    }
    var protocol = MockServerWebExchange.from(MockServerHttpRequest.get("/oauth2/authorize"));
    assertThatThrownBy(
            () ->
                filter
                    .filter(protocol, ignored -> Mono.error(new IllegalStateException("protocol")))
                    .block())
        .isInstanceOf(IllegalStateException.class);
    var stream = MockServerWebExchange.from(MockServerHttpRequest.get("/api/stream"));
    assertThatThrownBy(
            () ->
                filter
                    .filter(
                        stream,
                        ignored ->
                            stream
                                .getResponse()
                                .setComplete()
                                .then(Mono.error(new IllegalStateException("stream"))))
                    .block())
        .isInstanceOf(IllegalStateException.class);
  }
}
