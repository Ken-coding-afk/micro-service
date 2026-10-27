package com.ken.platform.foundation.http;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ServiceCallContractTest {
  @Test
  void extremeExpiredDeadlineReturnsTimeoutWithoutDurationOverflowOrIo() {
    var web =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  throw new AssertionError("过期请求不能发到上游");
                })
            .build();
    var client = new ServiceHttpClient(directory(), web, Clock.systemUTC(), Duration.ofSeconds(1));
    var expired = new CallContext("request-1", null, null, null, null, Instant.MIN);
    assertThatThrownBy(() -> client.request("analysis", "GET", "/result", null, expired).join())
        .hasCauseInstanceOf(PlatformException.class)
        .satisfies(
            error -> assertThat(((PlatformException) error.getCause()).status()).isEqualTo(504));
  }

  @Test
  void actualTransportDoesNotReplayPostAfterServerCommitsThenDisconnects() {
    var commits = new java.util.concurrent.atomic.AtomicInteger();
    var server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) -> {
                  commits.incrementAndGet();
                  request.withConnection(connection -> connection.dispose());
                  return Mono.empty();
                })
            .bindNow();
    try {
      var directory = new ServiceDiscovery(System::nanoTime);
      directory.confirm(
          "analysis", List.of(new ServiceInstance("127.0.0.1", server.port(), "http")));
      var client =
          ServiceHttpClient.configured(
              directory,
              Clock.systemUTC(),
              com.ken.platform.foundation.config.RuntimeParameters.defaults());
      assertThatThrownBy(
              () -> client.request("analysis", "POST", "/jobs", new byte[] {1}, null).join())
          .hasCauseInstanceOf(PlatformException.class);
      assertThat(commits.get()).isEqualTo(1);
    } finally {
      server.disposeNow();
    }
  }

  private ServiceDiscovery directory() {
    var directory = new ServiceDiscovery(System::nanoTime);
    directory.confirm(
        "analysis",
        List.of(
            new ServiceInstance("first.example", 8100, "http"),
            new ServiceInstance("second.example", 8100, "http")));
    return directory;
  }

  @Test
  void getRetriesOnlyOnceAndForwardsVerifiedContext() {
    List<ClientRequest> requests = new ArrayList<>();
    var web =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  requests.add(request);
                  return requests.size() == 1
                      ? Mono.error(new java.io.IOException("offline"))
                      : Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build());
                })
            .build();
    var client = new ServiceHttpClient(directory(), web, Clock.systemUTC(), Duration.ofSeconds(1));
    var context =
        new CallContext(
            "request-1",
            UUID.randomUUID(),
            "u1",
            "verified-fixture",
            null,
            Instant.now().plusSeconds(1));
    assertThat(
            client
                .request("analysis", "GET", "/api/v1/result", null, context)
                .join()
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).url().getHost()).isNotEqualTo(requests.get(1).url().getHost());
    assertThat(requests.get(1).headers().getFirst("Authorization"))
        .isEqualTo("Bearer verified-fixture");
    assertThat(requests.get(1).headers().getFirst("X-Tenant-Id")).isNull();
    assertThat(context.toString()).doesNotContain("verified-fixture");
  }

  @Test
  void postTimeoutNeverRepeatsCommittedOperation() {
    List<ClientRequest> commits = new ArrayList<>();
    var web =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  commits.add(request);
                  return Mono.never();
                })
            .build();
    var client = new ServiceHttpClient(directory(), web, Clock.systemUTC(), Duration.ofMillis(30));
    assertThatThrownBy(
            () -> client.request("analysis", "POST", "/jobs", new byte[] {1}, null).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PlatformException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformException) failure.getCause()).status()).isEqualTo(504));
    assertThat(commits).hasSize(1);
  }

  @Test
  void expiredBudgetDoesNotSendAndCancellationReachesTransport() {
    var cancelled = new AtomicBoolean();
    List<ClientRequest> requests = new ArrayList<>();
    var web =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  requests.add(request);
                  return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
                })
            .build();
    var client = new ServiceHttpClient(directory(), web, Clock.systemUTC(), Duration.ofSeconds(1));
    var expired =
        new CallContext("request-1", null, null, null, null, Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> client.request("analysis", "GET", "/result", null, expired).join())
        .hasCauseInstanceOf(PlatformException.class);
    assertThat(requests).isEmpty();
    var pending = client.request("analysis", "POST", "/jobs", null, null);
    pending.cancel(true);
    assertThat(cancelled).isTrue();
    assertThat(requests).hasSize(1);
    assertThatThrownBy(
            () -> client.request("analysis", "GET", "//other.example/private", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
