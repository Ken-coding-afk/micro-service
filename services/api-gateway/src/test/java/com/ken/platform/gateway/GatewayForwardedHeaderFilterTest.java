package com.ken.platform.gateway;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.gateway.config.ExternalBoundaryFilter;
import com.ken.platform.gateway.security.GatewayForwardedHeaderFilter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class GatewayForwardedHeaderFilterTest {
  private static final List<String> HEADERS =
      List.of(
          "Forwarded",
          "X-Forwarded-For",
          "X-Forwarded-Host",
          "X-Forwarded-Proto",
          "X-Forwarded-Port",
          "X-Forwarded-Prefix",
          "X-Forwarded-Ssl",
          "X-Real-IP",
          "Front-End-Https");

  private GatewayForwardedHeaderFilter filter(String proxies) {
    return new GatewayForwardedHeaderFilter(
        new MockEnvironment().withProperty("TRUSTED_PROXY_CIDRS", proxies));
  }

  static Stream<Arguments> peers() {
    return Stream.of(
        Arguments.of("", "192.0.2.17", false),
        Arguments.of("192.0.2.0/24", "192.0.2.17", true),
        Arguments.of("192.0.2.0/24", "198.51.100.8", false),
        Arguments.of("192.0.2.17", "192.0.2.17", true),
        Arguments.of("192.0.2.17", "192.0.2.18", false),
        Arguments.of("2001:db8:1::/48", "2001:db8:1:7::5", true),
        Arguments.of("2001:db8:1::/48", "2001:db8:2::5", false),
        Arguments.of("192.0.2.17,2001:db8:2::/64", "2001:db8:2::5", true),
        Arguments.of("127.0.0.1", null, false),
        Arguments.of("127.0.0.1", "unresolved", false));
  }

  @ParameterizedTest
  @MethodSource("peers")
  void onlyActualResolvedPeerCanAuthorizeForwardedHeaders(
      String proxies, String peer, boolean trusted) throws Exception {
    var request = MockServerHttpRequest.get("/api/v1/documents/item");
    HEADERS.forEach(name -> request.header(name, "127.0.0.1"));
    request
        .header("Authorization", "Bearer fixture")
        .header("Cookie", "BFF=fixture")
        .header("X-Request-Id", "proxy-test");
    if (peer != null)
      request.remoteAddress(
          peer.equals("unresolved")
              ? InetSocketAddress.createUnresolved("not-a-proxy.example", 8080)
              : new InetSocketAddress(InetAddress.getByName(peer), 8080));
    filter(proxies)
        .filter(
            MockServerWebExchange.from(request),
            exchange -> {
              var headers = exchange.getRequest().getHeaders();
              for (String name : HEADERS)
                assertThat(headers.containsHeader(name)).as(name).isEqualTo(trusted);
              assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer fixture");
              assertThat(headers.getFirst("Cookie")).isEqualTo("BFF=fixture");
              assertThat(headers.getFirst("X-Request-Id")).isEqualTo("proxy-test");
              return Mono.empty();
            })
        .block();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "proxy.example",
        "0.0.0.0/0",
        "::/0",
        "127.0.0.1/33",
        "::1/129",
        "127.0.0.1,",
        "127.0.0.1/-1",
        "127.0.0.1/+1",
        "127.0.0.1/",
        "127.0.0.1/32/1",
        "/32"
      })
  void invalidOrUnlimitedProxyRangeStopsStartup(String configured) {
    assertThatThrownBy(() -> filter(configured))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasNoCause()
        .hasMessage("Gateway 可信代理必须是明确 IP/CIDR，不能信任整个互联网");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void realNettyPeerControlsTrustAndIdentityHeadersAreRemovedEvenForTrustedProxy(boolean trusted)
      throws Exception {
    var json = JsonMapper.builder().build();
    var handler =
        WebHttpHandlerBuilder.webHandler(
                exchange -> {
                  var headers = exchange.getRequest().getHeaders();
                  var body =
                      Map.of(
                          "forwarded",
                          headers.containsHeader("Forwarded"),
                          "xff",
                          headers.containsHeader("X-Forwarded-For"),
                          "identity_absent",
                          !headers.containsHeader("X-User-Id"),
                          "bearer",
                          "Bearer fixture".equals(headers.getFirst("Authorization")),
                          "tcp_peer",
                          exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
                  return exchange
                      .getResponse()
                      .writeWith(
                          Mono.just(
                              exchange
                                  .getResponse()
                                  .bufferFactory()
                                  .wrap(json.writeValueAsBytes(body))));
                })
            .filter(new ExternalBoundaryFilter())
            .filter(filter(trusted ? "127.0.0.1/32" : "192.0.2.0/24"))
            .build();
    var server =
        reactor.netty.http.server.HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow();
    try (var http = HttpClient.newHttpClient()) {
      var response =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/api/v1/documents/item"))
                  .header("Forwarded", "for=192.0.2.17;host=edge.example;proto=https")
                  .header("X-Forwarded-For", "192.0.2.17")
                  .header("X-User-Id", "forged")
                  .header("Authorization", "Bearer fixture")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      var body = json.readTree(response.body());
      assertThat(body.get("forwarded").asBoolean()).isEqualTo(trusted);
      assertThat(body.get("xff").asBoolean()).isEqualTo(trusted);
      assertThat(body.get("identity_absent").asBoolean()).isTrue();
      assertThat(body.get("bearer").asBoolean()).isTrue();
      assertThat(body.get("tcp_peer").asString()).isEqualTo("127.0.0.1");
    } finally {
      server.disposeNow();
    }
  }
}
