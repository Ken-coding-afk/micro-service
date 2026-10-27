package com.ken.platform.identity.config;

import com.ken.platform.identity.controller.login.*;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.service.login.*;
import com.ken.platform.identity.service.oauth.ProtocolAuthorizations;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.federation.enabled", havingValue = "true")
@Import(FederationController.class)
public class FederationConfiguration {
  @Bean
  FederationProviders federationProviders(ClientRegistrationRepository clients, Environment env) {
    return new FederationProviders(clients, env);
  }

  @Bean
  ExternalIdentities externalIdentities(
      JdbcTemplate jdbc,
      LoginStore store,
      PasswordLoginService logins,
      SecurityPolicies policies,
      ProtocolAuthorizations authorizations,
      Clock clock,
      PlatformTransactionManager manager,
      LoginProtection protection) {
    return new ExternalIdentities(
        jdbc, store, logins, policies, authorizations, clock, manager, protection);
  }

  @Bean
  @Order(0)
  SecurityFilterChain federation(
      HttpSecurity http,
      ClientRegistrationRepository clients,
      FederationProviders providers,
      ExternalIdentities identities,
      PasswordLoginService logins,
      SessionCleanup cleanup,
      BrowserSessionGuard guard,
      LoginFailures failures,
      Clock clock)
      throws Exception {
    var requests = new FederationRequests(clients, clock);
    var contexts = new HttpSessionSecurityContextRepository();
    http.securityMatcher(
        "/identity/external",
        "/identity/external/**",
        "/oauth2/authorization/*",
        "/login/oauth2/code/*");
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(
                    "/identity/external/start", "/oauth2/authorization/*", "/login/oauth2/code/*")
                .permitAll()
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        errors ->
            errors.authenticationEntryPoint(
                new org.springframework.security.web.authentication
                    .LoginUrlAuthenticationEntryPoint("/identity/login")));
    http.addFilterAfter(
        guard.filter(false), org.springframework.security.web.csrf.CsrfFilter.class);
    http.sessionManagement(
        session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
    http.oauth2Login(
        login -> {
          login
              .clientRegistrationRepository(clients)
              .loginPage("/identity/login")
              .authorizedClientRepository(
                  new OAuth2AuthorizedClientRepository() {
                    @Override
                    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                        String id,
                        org.springframework.security.core.Authentication auth,
                        jakarta.servlet.http.HttpServletRequest request) {
                      return null;
                    }

                    @Override
                    public void saveAuthorizedClient(
                        OAuth2AuthorizedClient client,
                        org.springframework.security.core.Authentication auth,
                        jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) {
                      /* 外部 token 仅供本次交换，不保存到 SSO。 */
                    }

                    @Override
                    public void removeAuthorizedClient(
                        String id,
                        org.springframework.security.core.Authentication auth,
                        jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) {}
                  })
              .authorizationEndpoint(
                  endpoint ->
                      endpoint
                          .authorizationRequestResolver(requests)
                          .authorizationRequestRepository(requests))
              .failureHandler(
                  (request, response, error) ->
                      externalFailure(failures, request, response, "EXTERNAL_IDENTITY_REJECTED"))
              .successHandler(
                  (request, response, authentication) -> {
                    PasswordLoginService.Identity created = null;
                    try {
                      if (!(request.getAttribute(FederationRequests.COMPLETED)
                              instanceof Map<?, ?> intent)
                          || !(authentication instanceof OAuth2AuthenticationToken external))
                        throw new IllegalArgumentException("缺少已验证请求");
                      String provider = (String) intent.get("provider");
                      if (!provider.equals(external.getAuthorizedClientRegistrationId()))
                        throw new IllegalArgumentException("提供方不一致");
                      providers.require(provider, (String) intent.get("tenant_code"));
                      UUID binding =
                          intent.get("binding_session") == null
                              ? null
                              : UUID.fromString((String) intent.get("binding_session"));
                      created =
                          identities.complete(
                              UUID.fromString((String) intent.get("tenant")),
                              provider,
                              providers.authority(provider),
                              external.getPrincipal().getName(),
                              binding);
                      response.setHeader("Cache-Control", "no-store");
                      if (binding != null) {
                        SecurityContextHolder.clearContext();
                        request.getSession().invalidate();
                        cleanup.clean(binding);
                        response.sendRedirect("/identity/login?external=bound");
                        return;
                      }
                      var local =
                          UsernamePasswordAuthenticationToken.authenticated(
                              created,
                              null,
                              List.of(
                                  new org.springframework.security.core.authority
                                      .SimpleGrantedAuthority("FACTOR_OAUTH2")));
                      local.setDetails("EXTERNAL");
                      var context = SecurityContextHolder.createEmptyContext();
                      context.setAuthentication(local);
                      SecurityContextHolder.setContext(context);
                      request
                          .getSession()
                          .setMaxInactiveInterval(
                              logins.bindRedisSession(request.getSession().getId()));
                      contexts.saveContext(context, request, response);
                      var cache =
                          new org.springframework.security.web.savedrequest
                              .HttpSessionRequestCache();
                      var saved = cache.getRequest(request, response);
                      if (saved
                              instanceof
                              org.springframework.security.web.savedrequest.DefaultSavedRequest
                                  original
                          && original
                              .getRequestURI()
                              .equals(request.getContextPath() + "/oauth2/authorize")) {
                        String query = original.getQueryString();
                        if (query != null && (query.contains("\r") || query.contains("\n")))
                          throw new IllegalArgumentException("授权请求无效");
                        response.sendRedirect(
                            "/oauth2/authorize" + (query == null ? "" : "?" + query));
                      } else response.sendRedirect("/identity/session");
                    } catch (RuntimeException error) {
                      if (created != null) {
                        try {
                          logins.revokeCurrentSession();
                          cleanup.clean(created.sessionId());
                        } catch (RuntimeException unavailable) {
                          /* 已有持久撤销/清理机制兜底。 */
                        }
                      }
                      externalFailure(
                          failures,
                          request,
                          response,
                          error instanceof com.ken.platform.foundation.http.PlatformException known
                              ? known.code()
                              : "EXTERNAL_IDENTITY_REJECTED");
                    }
                  });
          login.addObjectPostProcessor(
              new org.springframework.security.config.ObjectPostProcessor<
                  OAuth2LoginAuthenticationFilter>() {
                @Override
                public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                  filter.setSecurityContextRepository(new NullSecurityContextRepository());
                  return filter;
                }
              });
        });
    return http.build();
  }

  private static void externalFailure(
      LoginFailures failures,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      String code)
      throws java.io.IOException, jakarta.servlet.ServletException {
    boolean recorded = failures.recordExternal(code);
    failures.onAuthenticationFailure(
        request,
        response,
        new LoginAuthentication.Failure(
            recorded ? code : "SECURITY_AUDIT_UNAVAILABLE", recorded ? 401 : 503, 0, true));
  }
}
