package com.ken.platform.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GatewayTokenPreflightFilterTest {
  private Route identityRoute() {
    return Route.async()
        .id("existing-identity-token")
        .uri(URI.create("http://identity.invalid"))
        .predicate(exchange -> exchange.getRequest().getPath().value().equals("/oauth2/token"))
        .metadata("platform.identity-public", true)
        .build();
  }

  @Test
  void exactPreflightUsesExistingRouteAndNativeFilteringOnceWithoutSessionOrFallback() {
    var route = identityRoute();
    var handler = mock(FilteringWebHandler.class);
    var chain = mock(WebFilterChain.class);
    var exchange =
        MockServerWebExchange.builder(
                MockServerHttpRequest.options("/oauth2/token")
                    .header("Origin", "http://localhost:48083")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type"))
            .sessionManager(
                request -> Mono.error(new AssertionError("Preflight must not read session")))
            .build();
    when(handler.handle(exchange))
        .thenAnswer(
            invocation -> {
              assertThat(exchange.<Route>getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR))
                  .isSameAs(route);
              assertThat(exchange.getRequest().getHeaders().getOrigin())
                  .isEqualTo("http://localhost:48083");
              exchange
                  .getResponse()
                  .getHeaders()
                  .setAccessControlAllowOrigin("http://localhost:48083");
              return exchange.getResponse().setComplete();
            });
    var unmatched =
        Route.async()
            .id("other-identity")
            .uri(URI.create("http://unmatched.invalid"))
            .predicate(request -> false)
            .metadata("platform.identity-public", true)
            .build();
    new GatewayTokenPreflightFilter(() -> Flux.just(unmatched, route), handler)
        .filter(exchange, chain)
        .block();
    verify(handler, times(1)).handle(exchange);
    verifyNoInteractions(chain);
    assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("http://localhost:48083");
    assertThat(exchange.getResponse().getCookies()).isEmpty();
  }

  @Test
  void otherPathsMethodsAndOrdinaryRequestsKeepNormalSecurityPipeline() {
    var handler = mock(FilteringWebHandler.class);
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());
    var filter = new GatewayTokenPreflightFilter(() -> Flux.just(identityRoute()), handler);
    for (var request :
        List.of(
            MockServerHttpRequest.options("/identity/login")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "POST")
                .build(),
            MockServerHttpRequest.options("/oauth2/token/")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "POST")
                .build(),
            MockServerHttpRequest.options("/oauth2/token")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "PUT")
                .build(),
            MockServerHttpRequest.options("/oauth2/token").build(),
            MockServerHttpRequest.options("/oauth2/token")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "")
                .build(),
            MockServerHttpRequest.post("/oauth2/token").build())) {
      var exchange = MockServerWebExchange.from(request);
      filter.filter(exchange, chain).block();
      assertThat(exchange.getAttributes())
          .doesNotContainKey(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    }
    verify(chain, times(6)).filter(any());
    verifyNoInteractions(handler);
  }

  @Test
  void missingCanonicalRouteCannotSelectBusinessRouteOrCreatePermission() {
    var route =
        Route.async()
            .id("business")
            .uri(URI.create("http://business.invalid"))
            .predicate(exchange -> true)
            .build();
    var handler = mock(FilteringWebHandler.class);
    var chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.options("/oauth2/token")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "POST"));
    new GatewayTokenPreflightFilter(() -> Flux.just(route), handler)
        .filter(exchange, chain)
        .block();
    verify(chain).filter(exchange);
    verifyNoInteractions(handler);
    assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
  }
}
