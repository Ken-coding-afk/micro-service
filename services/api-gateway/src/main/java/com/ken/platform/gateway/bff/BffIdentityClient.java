package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.OutboundCallBudget;
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
      String subject,
      String type,
      UUID tenant,
      String client,
      Set<String> audiences,
      boolean monitoring) {
    public Identity(
        String subject, String type, UUID tenant, String client, Set<String> audiences) {
      this(subject, type, tenant, client, audiences, false);
    }

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
                          CallContext call = context.getOrDefault(CallContext.class, null);
                          var budget =
                              new OutboundCallBudget(
                                  clock,
                                  Duration.ofMillis(
                                      Math.max(
                                          1, (long) (parameters.responseBudgetSeconds() * 1000))),
                                  call);
                          if (budget.remaining().isZero()) return Mono.error(unavailable());
                          var instance = discovery.choose("identity-service");
                          var forwarded =
                              ClientRequest.from(request)
                                  .url(instance.origin().resolve(uri.getRawPath()))
                                  .headers(headers -> budget.writeHeaders(headers, call))
                                  .build();
                          return next.exchange(forwarded)
                              .timeout(budget.remaining())
                              .flatMap(
                                  response -> {
                                    Duration remaining = budget.remaining();
                                    if (remaining.isZero())
                                      return response.releaseBody().then(Mono.error(unavailable()));
                                    var bounded =
                                        response
                                            .mutate()
                                            .body(
                                                body ->
                                                    body.takeUntilOther(
                                                        Mono.delay(remaining)
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
    return application(token)
        .map(
            identity -> {
              boolean platform = client.getRegistrationId().equals("platform");
              if (!client.getClientId().equals(identity.client())
                  || !(platform
                      ? identity.type().equals("platform") && identity.tenant() == null
                      : identity.type().equals("user") && identity.tenant() != null))
                throw invalid();
              return identity;
            });
  }

  public Mono<Identity> application(String token) {
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
                          String subject = data.path("sub").asString();
                          UUID.fromString(subject);
                          String type = data.path("principal_type").asString();
                          UUID tenant =
                              data.path("tenant_id").isNull()
                                  ? null
                                  : UUID.fromString(data.path("tenant_id").asString());
                          var audiences = data.get("audiences");
                          if (audiences == null || !audiences.isObject()) throw unavailable();
                          return new Identity(
                              subject,
                              type,
                              tenant,
                              data.path("client_id").asString(),
                              Set.copyOf(audiences.propertyNames()),
                              monitoringAllowed(data));
                        } catch (PlatformException known) {
                          throw known;
                        } catch (Exception malformed) {
                          throw unavailable();
                        }
                      });
            })
        .onErrorMap(error -> error instanceof PlatformException ? error : unavailable());
  }

  static boolean monitoringAllowed(tools.jackson.databind.JsonNode data) {
    if (!"platform".equals(data.path("principal_type").asString())
        || !data.path("tenant_id").isNull()) return false;
    var resource = data.path("audiences").path("identity-service");
    boolean permission = false, scope = false;
    for (var value : resource.path("permissions"))
      permission |= "platform:monitoring:read".equals(value.asString());
    for (var value : resource.path("scopes"))
      scope |= "platform.monitoring".equals(value.asString());
    return permission && scope;
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
