package com.ken.platform.gateway.traffic;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

class GatewayTransportContractTest {
  @Test
  void nativeClientDoesNotReplayAConnectionReset() {
    var attempts = new AtomicInteger();
    var server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) -> {
                  attempts.incrementAndGet();
                  response.withConnection(connection -> connection.dispose());
                  return Mono.empty();
                })
            .bindNow();
    try {
      var client =
          new GatewayTrafficConfiguration()
              .platformGatewayHttpClient()
              .customize(HttpClient.newConnection());
      for (var method :
          new io.netty.handler.codec.http.HttpMethod[] {
            io.netty.handler.codec.http.HttpMethod.GET, io.netty.handler.codec.http.HttpMethod.POST
          }) {
        int before = attempts.get();
        assertThatThrownBy(
                () ->
                    client
                        .request(method)
                        .uri("http://127.0.0.1:" + server.port() + "/value")
                        .response()
                        .block(Duration.ofSeconds(4)))
            .isInstanceOf(RuntimeException.class);
        assertThat(attempts.get()).isEqualTo(before + 1);
      }
    } finally {
      server.disposeNow();
    }
  }

  @Test
  void responseBodyHasRealReadIdleTimeoutRatherThanOnlyHeaderTimeout() {
    var server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) ->
                    response
                        .header("Content-Type", "text/event-stream")
                        .sendString(Flux.concat(Mono.just("data: start\n\n"), Flux.never())))
            .bindNow();
    try {
      var client =
          new GatewayTrafficConfiguration()
              .platformGatewayHttpClient()
              .customize(HttpClient.newConnection());
      assertThatThrownBy(
              () ->
                  client
                      .get()
                      .uri("http://127.0.0.1:" + server.port() + "/stream")
                      .responseContent()
                      .aggregate()
                      .asString()
                      .block(Duration.ofSeconds(50)))
          .isInstanceOf(io.netty.handler.timeout.ReadTimeoutException.class);
    } finally {
      server.disposeNow();
    }
  }
}
