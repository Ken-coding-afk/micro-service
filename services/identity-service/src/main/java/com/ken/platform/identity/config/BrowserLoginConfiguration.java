package com.ken.platform.identity.config;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.login.BrowserSessionGuard;
import com.ken.platform.identity.controller.login.LoginAuthentication;
import com.ken.platform.identity.controller.login.LoginFailures;
import com.ken.platform.identity.controller.login.SessionFailureFilter;
import com.ken.platform.identity.controller.login.TrustedClientAddress;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.SessionCleanup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class BrowserLoginConfiguration {
  @Bean
  TrustedClientAddress trustedClientAddress(Environment environment) {
    return new TrustedClientAddress(environment.getProperty("TRUSTED_PROXY_CIDRS", ""));
  }

  @Bean
  LoginFailures loginFailures(SecurityAuditRepository audits, PlatformTransactionManager manager) {
    return new LoginFailures(audits, manager);
  }

  @Bean
  BrowserSessionGuard browserSessionGuard(
      PasswordLoginService service,
      SessionCleanup cleanup,
      com.ken.platform.identity.dal.mapper.oauth.ClientStore clients) {
    return new BrowserSessionGuard(service, cleanup, clients);
  }

  @Bean
  SessionFailureFilter sessionFailureFilter() {
    return new SessionFailureFilter();
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<SessionFailureFilter>
      sessionFailureRegistration(SessionFailureFilter filter) {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    registration.setOrder(filter.getOrder());
    return registration;
  }

  @Bean
  @Order(3)
  SecurityFilterChain browserLogin(
      HttpSecurity http,
      PasswordLoginService service,
      SessionCleanup cleanup,
      TrustedClientAddress source,
      LoginFailures failures,
      BrowserSessionGuard guard)
      throws Exception {
    http.securityMatcher(
        "/identity/login", "/identity/password/initial", "/identity/session", "/identity/logout");
    http.headers(
        headers ->
            headers.contentSecurityPolicy(
                csp ->
                    csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")));
    http.authenticationProvider(new LoginAuthentication(service));
    http.authorizeHttpRequests(
        requests ->
            requests.requestMatchers("/identity/login").permitAll().anyRequest().authenticated());
    http.exceptionHandling(
        errors ->
            errors.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/identity/login")));
    http.addFilterAfter(guard.filter(false), CsrfFilter.class);
    http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.newSession()));
    http.formLogin(
        form -> {
          form.addObjectPostProcessor(
              new org.springframework.security.config.ObjectPostProcessor<
                  org.springframework.security.web.authentication
                      .UsernamePasswordAuthenticationFilter>() {
                @Override
                public <
                        O extends
                            org.springframework.security.web.authentication
                                    .UsernamePasswordAuthenticationFilter>
                    O postProcess(O filter) {
                  // 失败页 forward 仍接受访问控制，但不能再次消费同一个登录 POST。
                  filter.setRequiresAuthenticationRequestMatcher(
                      request ->
                          request.getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST
                              && request.getMethod().equals("POST")
                              && request
                                  .getRequestURI()
                                  .equals(request.getContextPath() + "/identity/login"));
                  return filter;
                }
              });
          form.loginPage("/identity/login")
              .loginProcessingUrl("/identity/login")
              .authenticationDetailsSource(
                  request -> {
                    try {
                      return new LoginAuthentication.Details(
                          realm(request.getParameter("realm")),
                          request.getParameter("tenant_code"),
                          request.getParameter("username"),
                          source.resolve(request));
                    } catch (PlatformException rejected) {
                      throw new LoginAuthentication.Failure(
                          rejected.code(), rejected.status(), 0, false);
                    } catch (RuntimeException malformed) {
                      throw new LoginAuthentication.Failure("INVALID_CREDENTIALS", 401, 0, false);
                    }
                  })
              .failureHandler(failures)
              .successHandler(
                  (request, response, authentication) -> {
                    try {
                      var session = request.getSession(true);
                      session.setMaxInactiveInterval(service.bindRedisSession(session.getId()));
                      var identity = (PasswordLoginService.Identity) authentication.getPrincipal();
                      response.setHeader("Cache-Control", "no-store");
                      if (identity.restricted()) {
                        response.sendRedirect("/identity/password/initial");
                        return;
                      }
                      var saved = new HttpSessionRequestCache().getRequest(request, response);
                      if (saved
                              instanceof
                              org.springframework.security.web.savedrequest.DefaultSavedRequest
                                  original
                          && original
                              .getRequestURI()
                              .equals(request.getContextPath() + "/oauth2/authorize")) {
                        String query = original.getQueryString();
                        if (query != null && (query.contains("\r") || query.contains("\n")))
                          throw new PlatformException("INVALID_CREDENTIALS", "授权请求无效", 401);
                        response.sendRedirect(
                            "/oauth2/authorize" + (query == null ? "" : "?" + query));
                      } else response.sendRedirect("/identity/session");
                    } catch (PlatformException rejected) {
                      try {
                        var owner = service.revokeCurrentSession();
                        cleanup.clean(owner.sessionId());
                      } catch (RuntimeException unavailable) {
                        /* 不交付成功上下文；持久意图保留。 */
                      }
                      failures.onAuthenticationFailure(
                          request,
                          response,
                          new LoginAuthentication.Failure(
                              rejected.code(), rejected.status(), 0, true));
                    }
                  });
        });
    http.logout(
        logout ->
            logout
                .logoutUrl("/identity/logout")
                .addLogoutHandler(
                    (request, response, authentication) -> {
                      if (authentication != null
                          && authentication.getPrincipal()
                              instanceof PasswordLoginService.Identity) {
                        var owner = service.revokeCurrentSession();
                        cleanup.clean(owner.sessionId());
                      }
                    })
                .logoutSuccessUrl("/identity/login?logout")
                .permitAll());
    return http.build();
  }

  private static LoginRealm realm(String value) {
    try {
      return LoginRealm.valueOf(value);
    } catch (RuntimeException invalid) {
      return null;
    }
  }
}
