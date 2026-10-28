package com.ken.platform.gateway.bff;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class BffRelayTest {
  @Test
  void routeWithoutBffMetadataPassesThrough() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health"));
    var called = new java.util.concurrent.atomic.AtomicBoolean();
    new BffRelay(null, null)
        .filter(
            exchange,
            value -> {
              called.set(true);
              return Mono.empty();
            })
        .block();
    assertThat(called).isTrue();
  }

  @Test
  void managementNamespacesRejectForeignResourcesBeforeReadingTokens() {
    for (String path :
        new String[] {
          "/bff/identity/tenant/api/platform/users", "/bff/identity/platform/api/users"
        }) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
      var namespace = BffNamespace.forPath(path);
      exchange
          .getAttributes()
          .put(
              ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
              Route.async()
                  .id("test")
                  .uri("http://localhost")
                  .predicate(value -> true)
                  .metadata("bff.namespace", namespace.name())
                  .build());
      assertThatThrownBy(
              () ->
                  new BffRelay(null, null)
                      .filter(
                          exchange,
                          value -> Mono.error(new AssertionError("must reject before forwarding")))
                      .block())
          .isInstanceOfSatisfying(
              PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
    }
  }

  @Test
  void landingPagesAndCredentialClassificationPreserveProtocolDecisions() {
    assertThat(BffNamespace.WEB.landingPage().toString()).isEqualTo("/");
    assertThat(BffNamespace.TENANT.landingPage().toString()).isEqualTo("/identity/admin/");
    assertThat(BffNamespace.PLATFORM.landingPage().toString()).isEqualTo("/identity/admin/");
    assertThat(BffTokens.credentialError("invalid_grant")).isTrue();
    assertThat(BffTokens.credentialError("temporarily_unavailable")).isFalse();
  }
}
