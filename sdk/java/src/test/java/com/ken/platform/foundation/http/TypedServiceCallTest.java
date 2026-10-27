package com.ken.platform.foundation.http;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class TypedServiceCallTest {
  record Item(@JsonProperty(required = true) int id, String note) {}

  private ServiceHttpClient client(int status, String body) {
    var directory = new ServiceDiscovery(System::nanoTime);
    directory.confirm("peer", List.of(new ServiceInstance("peer.invalid", 80, "http")));
    var web =
        WebClient.builder()
            .exchangeFunction(
                request ->
                    Mono.just(
                        ClientResponse.create(
                                org.springframework.http.HttpStatusCode.valueOf(status))
                            .header("Content-Type", "application/json")
                            .body(body)
                            .build()))
            .build();
    return new ServiceHttpClient(directory, web, Clock.systemUTC(), Duration.ofSeconds(2));
  }

  @Test
  void genericListAndOptionalFieldAndUnknownField() {
    var value =
        client(200, "[{\"id\":7,\"future\":true}]")
            .request(
                "peer",
                "GET",
                "/items",
                null,
                null,
                new ParameterizedTypeReference<List<Item>>() {})
            .join();
    assertThat(value.getBody()).containsExactly(new Item(7, null));
  }

  @Test
  void invalidRequiredFieldRejected() {
    for (String body : List.of("{}", "{\"id\":null}", "{\"id\":\"7\"}", "null", "invalid", "")) {
      assertThatThrownBy(
              () ->
                  client(200, body)
                      .request(
                          "peer",
                          "GET",
                          "/items",
                          null,
                          null,
                          new ParameterizedTypeReference<Item>() {})
                      .join())
          .hasCauseInstanceOf(PlatformException.class)
          .satisfies(
              error ->
                  assertThat(((PlatformException) error.getCause()).code())
                      .isEqualTo("DEPENDENCY_INVALID_RESPONSE"));
    }
  }

  @Test
  void noContentAndSafeError() {
    assertThat(
            client(204, "")
                .request(
                    "peer",
                    "DELETE",
                    "/items",
                    null,
                    null,
                    new ParameterizedTypeReference<Void>() {})
                .join()
                .getBody())
        .isNull();
    assertThatThrownBy(
            () ->
                client(403, "{\"code\":\"NO_ACCESS\",\"message\":\"private-secret\"}")
                    .request(
                        "peer",
                        "GET",
                        "/items",
                        null,
                        null,
                        new ParameterizedTypeReference<Item>() {})
                    .join())
        .satisfies(
            error -> {
              var failure = (PlatformException) error.getCause();
              assertThat(failure.code()).isEqualTo("NO_ACCESS");
              assertThat(failure.status()).isEqualTo(403);
              assertThat(failure.getMessage()).doesNotContain("private-secret");
            });
  }
}
