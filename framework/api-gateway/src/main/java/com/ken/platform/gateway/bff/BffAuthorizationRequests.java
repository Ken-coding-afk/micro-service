package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.http.PlatformException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 登录是受 CSRF 保护的 POST；state/PKCE 由官方组件生成，入口固定选择客户端。 */
public final class BffAuthorizationRequests implements ServerOAuth2AuthorizationRequestResolver {
  private final BffClientSettings settings;
  private final ReactiveClientRegistrationRepository registrations;

  public BffAuthorizationRequests(
      BffClientSettings settings, ReactiveClientRegistrationRepository registrations) {
    this.settings = settings;
    this.registrations = registrations;
  }

  @Override
  public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
    var namespace = BffNamespace.forPath(exchange.getRequest().getPath().value());
    if (namespace == null
        || !settings.namespaces().contains(namespace)
        || !exchange.getRequest().getPath().value().equals(namespace.path() + "/login")
        || exchange.getRequest().getMethod() != HttpMethod.POST) return Mono.empty();
    return exchange
        .getFormData()
        .flatMap(
            form -> {
              if (!Set.of("tenant_code", "_csrf").containsAll(form.keySet())
                  || form.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
              String tenant = form.getFirst("tenant_code");
              if (namespace == BffNamespace.PLATFORM
                  ? tenant != null && !tenant.isBlank()
                  : tenant == null || !tenant.matches("[a-z][a-z0-9-]{0,63}")) throw invalid();
              // 每个请求独立构造，在官方生成授权 URL 前设置参数，避免并发切换租户或沿用旧 URI。
              var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
              resolver.setAuthorizationRequestCustomizer(
                  OAuth2AuthorizationRequestCustomizers.withPkce()
                      .andThen(
                          builder ->
                              builder.additionalParameters(
                                  parameters -> {
                                    if (namespace != BffNamespace.PLATFORM)
                                      parameters.put("tenant_code", tenant);
                                    parameters.put("prompt", "login");
                                  })));
              return resolver.resolve(exchange, namespace.name().toLowerCase(Locale.ROOT));
            });
  }

  @Override
  public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String ignored) {
    return resolve(exchange);
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_REQUEST", "请选择有效租户或平台入口，登录参数不得包含凭据", 400);
  }
}
