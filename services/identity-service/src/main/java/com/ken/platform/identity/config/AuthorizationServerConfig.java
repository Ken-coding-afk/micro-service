package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.oauth.AuthorizationErrorHandler;
import com.ken.platform.identity.service.oauth.ControlledRegisteredClients;
import com.ken.platform.identity.service.oauth.ProtocolAuthorizations;
import com.ken.platform.identity.service.oauth.ProtocolErrorHandler;
import com.ken.platform.identity.service.oauth.ProtocolProvider;
import com.ken.platform.identity.service.oauth.UserProtocolProvider;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;

/** 标准授权服务器装配；受控用户/机器协议边界，应用接口默认拒绝。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(OidcClaimsConfiguration.class)
public class AuthorizationServerConfig {
  @Bean
  RegisteredClientRepository registeredClientRepository(ClientStore store) {
    return new ControlledRegisteredClients(store);
  }

  @Bean
  ProtocolAuthorizations protocolAuthorizations(
      JdbcTemplate jdbc, RegisteredClientRepository clients, ClientStore store) {
    return new ProtocolAuthorizations(jdbc, clients, store);
  }

  @Bean
  OAuth2AuthorizationConsentService authorizationConsentService(
      JdbcTemplate jdbc, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(Environment environment) {
    String appEnv = environment.getRequiredProperty("APP_ENV");
    if (!Set.of("local", "test", "prod").contains(appEnv))
      throw new IllegalArgumentException("未知认证环境");
    String value = environment.getRequiredProperty("PUBLIC_ORIGIN");
    URI uri = URI.create(value);
    String host = uri.getHost();
    boolean local =
        "local".equals(appEnv)
            && host != null
            && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
    if (host == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !Set.of("", "/").contains(uri.getRawPath())
        || uri.getPort() > 65535
        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) {
      throw new IllegalArgumentException("PUBLIC_ORIGIN 必须是固定 HTTPS 来源，local 仅 loopback HTTP");
    }
    String issuer = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }

  @Bean
  @Order(1)
  SecurityFilterChain authorizationEndpoints(
      HttpSecurity http,
      ClientStore store,
      ProtocolAuthorizations authorizations,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager,
      ObjectProvider<PasswordLoginService> logins,
      ObjectProvider<LoginStore> sessions,
      ObjectProvider<com.ken.platform.identity.service.oauth.UserTokenService> userTokens,
      ObjectProvider<com.ken.platform.identity.service.login.SessionCleanup> cleanup,
      org.springframework.beans.factory.ObjectProvider<
              com.ken.platform.identity.controller.login.BrowserSessionGuard>
          browserGuard)
      throws Exception {
    var errors = new ProtocolErrorHandler(audits, manager);
    var users = userTokens.getIfAvailable();
    var resourceErrors =
        new com.ken.platform.identity.service.oauth.ResourceProtocolErrors(audits, manager);
    http.addFilterBefore(
        new com.ken.platform.identity.controller.login.ConsentCsrfFilter(audits, manager),
        org.springframework.security.web.csrf.CsrfFilter.class);
    http.oauth2AuthorizationServer(
        server -> {
          server
              .authorizationEndpoint(
                  endpoint ->
                      endpoint
                          .errorResponseHandler(new AuthorizationErrorHandler(audits, manager))
                          .authenticationProviders(
                              providers ->
                                  wrapUsers(
                                      providers,
                                      store,
                                      authorizations,
                                      audits,
                                      manager,
                                      logins.getIfAvailable(),
                                      sessions.getIfAvailable(),
                                      false)))
              .clientAuthentication(
                  endpoint ->
                      endpoint
                          .authenticationProviders(
                              providers ->
                                  wrap(
                                      providers,
                                      ProtocolProvider.Operation.CLIENT_AUTHENTICATION,
                                      store,
                                      authorizations,
                                      audits,
                                      manager))
                          .errorResponseHandler(errors))
              .tokenEndpoint(
                  endpoint ->
                      endpoint
                          .authenticationProviders(
                              providers ->
                                  wrapUsers(
                                      providers,
                                      store,
                                      authorizations,
                                      audits,
                                      manager,
                                      logins.getIfAvailable(),
                                      sessions.getIfAvailable(),
                                      true))
                          .errorResponseHandler(errors))
              .tokenIntrospectionEndpoint(
                  endpoint ->
                      endpoint
                          .authenticationProviders(
                              providers ->
                                  wrapIntrospection(
                                      providers, store, authorizations, audits, manager, users))
                          .errorResponseHandler(errors))
              .tokenRevocationEndpoint(
                  endpoint ->
                      endpoint
                          .authenticationProviders(
                              providers ->
                                  wrap(
                                      providers,
                                      ProtocolProvider.Operation.REVOCATION,
                                      store,
                                      authorizations,
                                      audits,
                                      manager))
                          .errorResponseHandler(errors));
          if (users != null) {
            server.oidc(
                oidc ->
                    oidc.userInfoEndpoint(
                            endpoint ->
                                endpoint
                                    .userInfoMapper(
                                        context ->
                                            new org.springframework.security.oauth2.core.oidc
                                                .OidcUserInfo(
                                                users.userInfoClaims(
                                                    context.getAccessToken().getTokenValue())))
                                    .errorResponseHandler(resourceErrors))
                        .logoutEndpoint(
                            endpoint ->
                                endpoint
                                    .errorResponseHandler(errors)
                                    .authenticationProviders(
                                        providers ->
                                            providers.replaceAll(
                                                provider ->
                                                    new com.ken.platform.identity.service.oauth
                                                        .OidcSessionLogoutProvider(
                                                        provider,
                                                        authorizations,
                                                        logins.getObject(),
                                                        cleanup.getObject())))));
          }
        });
    if (users != null)
      http.oauth2ResourceServer(
          resource ->
              resource
                  // SAS 7.0 自动启用该 DSL；显式替换认证管理器，实际只注册 opaque Provider。
                  .jwt(
                      jwt ->
                          jwt.authenticationManager(
                              new org.springframework.security.authentication.ProviderManager(
                                  new org.springframework.security.oauth2.server.resource
                                      .authentication.OpaqueTokenAuthenticationProvider(
                                      users::userInfoPrincipal))))
                  .authenticationEntryPoint(resourceErrors));
    http.headers(
        headers ->
            headers.contentSecurityPolicy(
                csp ->
                    csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")));
    http.securityMatcher(
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).getEndpointsMatcher());
    var guard = browserGuard.getIfAvailable();
    if (guard != null) {
      http.addFilterAfter(
          guard.filter(true), org.springframework.security.web.csrf.CsrfFilter.class);
      http.exceptionHandling(
          exceptions ->
              exceptions.defaultAuthenticationEntryPointFor(
                  new org.springframework.security.web.authentication
                      .LoginUrlAuthenticationEntryPoint("/identity/login"),
                  new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
                      org.springframework.http.MediaType.TEXT_HTML)));
    }
    http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
    return http.build();
  }

  @Bean
  @Order(4)
  SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
    http.csrf(
            org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
                ::disable)
        .formLogin(
            org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
                ::disable)
        .httpBasic(
            org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
                ::disable)
        .logout(
            org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
                ::disable)
        .requestCache(
            org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
                ::disable)
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .securityContext(
            context ->
                context.securityContextRepository(
                    new org.springframework.security.web.context.NullSecurityContextRepository()));
    http.headers(
        headers ->
            headers
                .contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(
                    policy ->
                        policy.policy(
                            org.springframework.security.web.header.writers
                                .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers(
                    request ->
                        request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR
                            && Integer.valueOf(404)
                                .equals(
                                    request.getAttribute(
                                        jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE))
                            && request.getAttribute(
                                    jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI)
                                instanceof String original
                            && original.startsWith(request.getContextPath() + "/identity/admin/"))
                .permitAll()
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness")
                .permitAll()
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/identity/admin",
                    "/identity/admin/**")
                .permitAll()
                .anyRequest()
                .denyAll());
    return http.build();
  }

  private static void wrap(
      List<AuthenticationProvider> providers,
      ProtocolProvider.Operation operation,
      ClientStore store,
      ProtocolAuthorizations authorizations,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager) {
    providers.replaceAll(
        provider ->
            new ProtocolProvider(provider, operation, store, authorizations, audits, manager));
  }

  private static void wrapUsers(
      List<AuthenticationProvider> providers,
      ClientStore store,
      ProtocolAuthorizations authorizations,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager,
      PasswordLoginService logins,
      LoginStore sessions,
      boolean token) {
    providers.replaceAll(
        provider ->
            UserProtocolProvider.userProvider(provider)
                ? new UserProtocolProvider(
                    provider, store, authorizations, logins, sessions, audits, manager)
                : token
                    ? new ProtocolProvider(
                        provider,
                        ProtocolProvider.Operation.TOKEN,
                        store,
                        authorizations,
                        audits,
                        manager)
                    : provider);
  }

  private static void wrapIntrospection(
      List<AuthenticationProvider> providers,
      ClientStore store,
      ProtocolAuthorizations authorizations,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager,
      com.ken.platform.identity.service.oauth.UserTokenService users) {
    providers.replaceAll(
        provider -> {
          var machines =
              new ProtocolProvider(
                  provider,
                  ProtocolProvider.Operation.INTROSPECTION,
                  store,
                  authorizations,
                  audits,
                  manager);
          return users == null
              ? machines
              : new com.ken.platform.identity.service.oauth.UserIntrospectionProvider(
                  machines, users);
        });
  }
}
