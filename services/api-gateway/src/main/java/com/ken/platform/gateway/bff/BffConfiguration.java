package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.gateway.security.GatewayErrors;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/** 固定 BFF 可独立启用；未提供受控客户端描述时保持现有 Bearer Gateway 装配。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "GATEWAY_BFF_CLIENTS_SECRET_FILE")
public class BffConfiguration {
  @Bean
  BffClientSettings bffClients(Environment environment) {
    return new BffClientSettings(environment);
  }

  @Bean
  ReactiveRedisTemplate<String, Object> bffSessionRedis(
      @Qualifier("bffRedisConnectionFactory") LettuceConnectionFactory factory) {
    var strings = new StringRedisSerializer();
    var serializer = BffSessions.serializer();
    return new ReactiveRedisTemplate<>(
        factory,
        RedisSerializationContext.<String, Object>newSerializationContext(strings)
            .value(serializer)
            .hashKey(strings)
            .hashValue(serializer)
            .build());
  }

  @Bean(name = "webSessionManager")
  BffSessions bffSessions(
      @Qualifier("bffSessionRedis") ReactiveRedisTemplate<String, Object> redis,
      Environment environment,
      BffClientSettings settings,
      Clock clock) {
    return new BffSessions(
        redis,
        environment.getRequiredProperty("PROJECT_CODE"),
        environment.getRequiredProperty("APP_ENV"),
        settings.secure(),
        clock);
  }

  @Bean
  ReactiveClientRegistrationRepository bffRegistrations(BffClientSettings settings) {
    return settings.repository();
  }

  @Bean
  ServerOAuth2AuthorizedClientRepository bffAuthorizedClients() {
    return new WebSessionServerOAuth2AuthorizedClientRepository();
  }

  @Bean
  BffIdentityClient bffIdentity(
      ServiceDiscovery discovery, RuntimeParameters parameters, Clock clock) {
    return new BffIdentityClient(discovery, parameters, clock);
  }

  @Bean
  BffTokens bffTokens(
      BffSessions sessions,
      ServerOAuth2AuthorizedClientRepository repository,
      ReactiveStringRedisTemplate locks,
      ReactiveClientRegistrationRepository registrations,
      BffIdentityClient identity,
      RuntimeParameters parameters,
      Clock clock,
      Environment environment) {
    return new BffTokens(
        sessions,
        repository,
        locks,
        registrations,
        identity,
        parameters,
        clock,
        environment.getRequiredProperty("PROJECT_CODE"),
        environment.getRequiredProperty("APP_ENV"));
  }

  @Bean
  PlatformReadinessCheck bffDependencies(
      NacosRuntime runtime,
      BffClientSettings settings,
      BffIdentityClient identity,
      @Qualifier("bffSessionRedis") ReactiveRedisTemplate<String, Object> redis) {
    return () -> {
      try {
        runtime.watch("identity-service");
      } catch (Exception unavailable) {
        throw new IllegalStateException("BFF 身份服务发现尚未就绪");
      }
      if (!"PONG"
          .equals(
              redis.execute(connection -> connection.ping()).next().block(Duration.ofSeconds(5))))
        throw new IllegalStateException("BFF 会话存储尚未就绪");
      for (var namespace : settings.namespaces())
        identity.verify(settings.client(namespace)).block(Duration.ofSeconds(10));
    };
  }

  @Bean
  BffRelay bffRelay(BffTokens tokens, BffIdentityClient identity) {
    return new BffRelay(tokens, identity);
  }

  @Bean
  org.springframework.cloud.gateway.route.RouteDefinitionLocator bffRoutes(
      BffClientSettings settings, com.ken.platform.gateway.security.GatewayRoutes routes) {
    return () -> reactor.core.publisher.Flux.fromIterable(routes.bffRoutes(settings.namespaces()));
  }

  @Bean
  @Order(0)
  SecurityWebFilterChain browserBff(
      ServerHttpSecurity http,
      BffClientSettings settings,
      ReactiveClientRegistrationRepository registrations,
      ServerOAuth2AuthorizedClientRepository clients,
      BffIdentityClient identity,
      ReactiveStringRedisTemplate locks,
      Environment environment) {
    var errors = new GatewayErrors();
    var csrfTokens = new WebSessionServerCsrfTokenRepository();
    String[] paths =
        settings.namespaces().stream()
            .map(namespace -> namespace.path() + "/**")
            .toArray(String[]::new);
    http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths))
        .securityContextRepository(new WebSessionServerSecurityContextRepository())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).accessDeniedHandler(errors))
        .exceptionHandling(
            exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    http.authorizeExchange(
        requests -> {
          for (var namespace : settings.namespaces()) {
            requests
                .pathMatchers(
                    HttpMethod.GET,
                    namespace.path() + "/csrf",
                    namespace.path() + "/callback",
                    namespace.path() + "/logged-out")
                .permitAll();
            requests.pathMatchers(HttpMethod.POST, namespace.path() + "/login").permitAll();
          }
          requests.anyExchange().authenticated();
        });
    http.addFilterAt(
        (exchange, chain) -> {
          if (exchange.getRequest().getHeaders().containsHeader("Authorization")
              || exchange.getRequest().getQueryParams().keySet().stream()
                  .anyMatch(Set.of("access_token", "refresh_token", "id_token")::contains))
            return Mono.error(
                new PlatformException("INVALID_REQUEST", "浏览器 BFF 仅接受对应会话 Cookie", 400));
          exchange.getResponse().getHeaders().setCacheControl("no-store");
          exchange.getResponse().getHeaders().set("Referrer-Policy", "no-referrer");
          return chain.filter(exchange);
        },
        SecurityWebFiltersOrder.FIRST);
    var requests = new BffAuthorizationRequests(settings, registrations);
    var token = new WebClientReactiveAuthorizationCodeTokenResponseClient();
    token.setWebClient(identity.web());
    var authentication =
        new OAuth2LoginReactiveAuthenticationManager(
            request ->
                token
                    .getTokenResponse(request)
                    .onErrorMap(
                        failure -> {
                          if (failure instanceof OAuth2AuthorizationException oauth
                              && Set.of("invalid_grant", "invalid_client", "invalid_scope")
                                  .contains(oauth.getError().getErrorCode()))
                            return new OAuth2AuthenticationException(oauth.getError());
                          return new AuthenticationServiceException("身份服务暂不可用");
                        }),
            identity::loginUser);
    http.oauth2Login(
        oauth ->
            oauth
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(clients)
                .authorizationRequestRepository(
                    new BffAuthorizationRequestsRepository(
                        locks,
                        environment.getRequiredProperty("PROJECT_CODE"),
                        environment.getRequiredProperty("APP_ENV")))
                .authorizationRequestResolver(requests)
                .authenticationMatcher(
                    ServerWebExchangeMatchers.pathMatchers(
                        HttpMethod.GET,
                        settings.namespaces().stream()
                            .map(namespace -> namespace.path() + "/callback")
                            .toArray(String[]::new)))
                .authenticationManager(authentication)
                .authenticationFailureHandler(
                    (exchange, failure) -> errors.commence(exchange.getExchange(), failure))
                .authenticationSuccessHandler(
                    (exchange, authenticationResult) ->
                        exchange
                            .getExchange()
                            .getSession()
                            .flatMap(
                                session ->
                                    csrfTokens
                                        .saveToken(exchange.getExchange(), null)
                                        .then(session.changeSessionId())
                                        .then(
                                            Mono.defer(
                                                () -> {
                                                  var namespace =
                                                      BffNamespace.forPath(
                                                          exchange
                                                              .getExchange()
                                                              .getRequest()
                                                              .getPath()
                                                              .value());
                                                  exchange
                                                      .getExchange()
                                                      .getResponse()
                                                      .setStatusCode(HttpStatus.SEE_OTHER);
                                                  exchange
                                                      .getExchange()
                                                      .getResponse()
                                                      .getHeaders()
                                                      .setLocation(
                                                          java.net.URI.create(
                                                              namespace == BffNamespace.WEB
                                                                  ? "/"
                                                                  : "/identity/admin/"));
                                                  return exchange
                                                      .getExchange()
                                                      .getResponse()
                                                      .setComplete();
                                                })))));
    return http.build();
  }
}
