package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.PlatformException;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 所有控制请求使用有界发现与真正异步 HTTP；不重试 token/撤销写入，不缓存成功身份。 */
public final class BffIdentityClient {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> PATHS =
      Set.of("/oauth2/token", "/oauth2/revoke", "/api/v1/identity/me");
  private final WebClient web;

  public record Identity(
      String subject, String type, UUID tenant, String client, Set<String> audiences) {
    public Identity {
      audiences = Set.copyOf(audiences);
    }

    public Map<String, Object> attributes() {
      var values = new HashMap<String, Object>();
      values.put("sub", subject);
      values.put("principal_type", type);
      values.put("client_id", client);
      if (tenant != null) values.put("tenant_id", tenant.toString());
      return Map.copyOf(values);
    }
  }

  public BffIdentityClient(ServiceDiscovery discovery, RuntimeParameters parameters, Clock clock) {
    var client =
        reactor.netty.http.client.HttpClient.create()
            .disableRetry(true)
            .followRedirect(false)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.max(1, (int) (parameters.connectTimeoutSeconds() * 1000)))
            .responseTimeout(
                Duration.ofMillis(Math.max(1, (long) (parameters.responseBudgetSeconds() * 1000))));
    web =
        WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(client))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .filter(
                (request, next) ->
                    Mono.deferContextual(
                        context -> {
                          URI uri = request.url();
                          if (!"http".equals(uri.getScheme())
                              || !"identity-service".equals(uri.getHost())
                              || uri.getPort() != -1
                              || uri.getRawUserInfo() != null
                              || uri.getRawQuery() != null
                              || uri.getRawFragment() != null
                              || !PATHS.contains(uri.getRawPath()))
                            return Mono.error(unavailable());
                          var instance = discovery.choose("identity-service");
                          CallContext call = context.getOrDefault(CallContext.class, null);
                          Duration budget =
                              Duration.ofMillis(
                                  Math.max(1, (long) (parameters.responseBudgetSeconds() * 1000)));
                          if (call != null && call.deadline() != null) {
                            Duration remaining = Duration.between(clock.instant(), call.deadline());
                            if (remaining.isNegative() || remaining.isZero())
                              return Mono.error(unavailable());
                            if (remaining.compareTo(budget) < 0) budget = remaining;
                          }
                          var forwarded =
                              ClientRequest.from(request)
                                  .url(instance.origin().resolve(uri.getRawPath()))
                                  .headers(
                                      headers -> {
                                        if (call != null) {
                                          headers.set("X-Request-Id", call.requestId());
                                          if (call.traceparent() != null)
                                            headers.set("traceparent", call.traceparent());
                                          if (call.deadline() != null)
                                            headers.set(
                                                "X-Request-Deadline",
                                                Long.toString(call.deadline().toEpochMilli()));
                                        }
                                      })
                                  .build();
                          Duration totalBudget = budget;
                          long started = System.nanoTime();
                          return next.exchange(forwarded)
                              .timeout(budget)
                              .flatMap(
                                  response -> {
                                    long remaining =
                                        totalBudget.toNanos() - (System.nanoTime() - started);
                                    if (remaining <= 0)
                                      return response.releaseBody().then(Mono.error(unavailable()));
                                    var bounded =
                                        response
                                            .mutate()
                                            .body(
                                                body ->
                                                    body.takeUntilOther(
                                                        Mono.delay(Duration.ofNanos(remaining))
                                                            .then(Mono.error(unavailable()))))
                                            .build();
                                    if (bounded.statusCode().is3xxRedirection()
                                        || bounded.statusCode().is5xxServerError())
                                      return bounded.releaseBody().then(Mono.error(unavailable()));
                                    return Mono.just(bounded);
                                  })
                              .onErrorMap(
                                  error ->
                                      error instanceof PlatformException ? error : unavailable());
                        }))
            .build();
  }

  public WebClient web() {
    return web;
  }

  public Mono<Identity> application(String token, ClientRegistration client) {
    return web.get()
        .uri("http://identity-service/api/v1/identity/me")
        .headers(headers -> headers.setBearerAuth(token))
        .exchangeToMono(
            response -> {
              if (response.statusCode().value() == 401)
                return response.releaseBody().then(Mono.error(invalid()));
              if (!response.statusCode().is2xxSuccessful())
                return response.releaseBody().then(Mono.error(unavailable()));
              return response
                  .bodyToMono(String.class)
                  .map(
                      body -> {
                        try {
                          var root = JSON.readTree(body);
                          var data = root.get("data");
                          if (!"OK".equals(root.path("code").asString())
                              || data == null
                              || !data.isObject()
                              || !"APPLICATION_EFFECTIVE".equals(data.path("view").asString()))
                            throw unavailable();
                          if (!client.getClientId().equals(data.path("client_id").asString()))
                            throw invalid();
                          String subject = data.path("sub").asString();
                          UUID.fromString(subject);
                          String type = data.path("principal_type").asString();
                          UUID tenant =
                              data.path("tenant_id").isNull()
                                  ? null
                                  : UUID.fromString(data.path("tenant_id").asString());
                          boolean platform = client.getRegistrationId().equals("platform");
                          if (!(platform
                              ? type.equals("platform") && tenant == null
                              : type.equals("user") && tenant != null)) throw invalid();
                          var audiences = data.get("audiences");
                          if (audiences == null || !audiences.isObject()) throw unavailable();
                          return new Identity(
                              subject,
                              type,
                              tenant,
                              client.getClientId(),
                              Set.copyOf(audiences.propertyNames()));
                        } catch (PlatformException known) {
                          throw known;
                        } catch (Exception malformed) {
                          throw unavailable();
                        }
                      });
            })
        .onErrorMap(error -> error instanceof PlatformException ? error : unavailable());
  }

  public Mono<OAuth2User> loginUser(OAuth2UserRequest request) {
    return application(request.getAccessToken().getTokenValue(), request.getClientRegistration())
        .<OAuth2User>map(
            identity ->
                new DefaultOAuth2User(
                    java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                            "ROLE_BFF_USER")),
                    identity.attributes(),
                    "sub"))
        .onErrorMap(
            error ->
                error instanceof PlatformException known && known.status() == 401
                    ? new OAuth2AuthenticationException("invalid_user_info_response")
                    : new AuthenticationServiceException("身份服务暂不可用"));
  }

  public Mono<Void> revoke(ClientRegistration client, String token) {
    return web.method(HttpMethod.POST)
        .uri("http://identity-service/oauth2/revoke")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .headers(headers -> headers.setBasicAuth(client.getClientId(), client.getClientSecret()))
        .body(BodyInserters.fromFormData("token", token))
        .exchangeToMono(
            response -> {
              if (!response.statusCode().is2xxSuccessful())
                return response.releaseBody().then(Mono.error(unavailable()));
              return response.releaseBody();
            })
        .onErrorMap(error -> error instanceof PlatformException ? error : unavailable());
  }

  public Mono<Void> verify(ClientRegistration client) {
    return revoke(client, "bff-startup-credential-check-no-live-authorization");
  }

  private static PlatformException unavailable() {
    return new PlatformException("BFF_IDENTITY_UNAVAILABLE", "身份服务暂不可用", 503);
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "当前应用身份无效，请重新登录", 401);
  }
}
