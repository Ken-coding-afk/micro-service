package com.ken.platform.identity.config;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.security.ResourceContextFilter;
import com.ken.platform.foundation.security.ResourceRequestGuard;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.time.Clock;
import java.util.Collections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** 本地实时 Bearer 授权与可信上下文；固定声明的 API 方法独立于登录 Cookie 和协议链。 */
@Configuration(proxyBeanMethods = false)
public class IdentityResourceConfiguration {
  @Bean
  @Order(2)
  SecurityFilterChain identityApplicationResource(
      HttpSecurity http,
      UserTokenService users,
      Clock clock,
      RuntimeParameters parameters,
      ManagementAudits audits)
      throws Exception {
    var errors = new IdentityResourceErrors(audits);
    http.securityMatcher("/api/v1/identity/**");
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable);
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.securityContext(
        context -> context.securityContextRepository(new NullSecurityContextRepository()));
    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers(HttpMethod.GET, "/api/v1/identity/admin-config")
                .permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/identity/platform/security-policy",
                    "/api/v1/identity/audits",
                    "/api/v1/identity/platform/audits")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/identity/platform/security-policy")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/identity/platform/admins",
                    "/api/v1/identity/platform/admins/*",
                    "/api/v1/identity/platform/tenants",
                    "/api/v1/identity/platform/tenants/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/identity/users",
                    "/api/v1/identity/platform/admins",
                    "/api/v1/identity/platform/tenants")
                .authenticated()
                .requestMatchers(
                    HttpMethod.PUT,
                    "/api/v1/identity/platform/admins/*",
                    "/api/v1/identity/platform/tenants/*")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/identity/me")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/identity/sessions")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/identity/sessions/*:revoke",
                    "/api/v1/identity/users/*:sign-out-all")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/identity/me/profile")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/identity/me/profile")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET, "/api/v1/identity/me/sessions", "/api/v1/identity/me/consents")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/identity/me/sessions/*:revoke",
                    "/api/v1/identity/me/sessions:sign-out-all",
                    "/api/v1/identity/me/consents/*:revoke",
                    "/api/v1/identity/me/password")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/identity/users",
                    "/api/v1/identity/users/*",
                    "/api/v1/identity/users/*/roles",
                    "/api/v1/identity/users/*/effective-permissions",
                    "/api/v1/identity/roles",
                    "/api/v1/identity/roles/*/members",
                    "/api/v1/identity/permissions",
                    "/api/v1/identity/role-permissions/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/identity/platform/oauth-clients",
                    "/api/v1/identity/platform/oauth-clients/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/identity/platform/oauth-clients",
                    "/api/v1/identity/platform/service-audiences")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST, "/api/v1/identity/platform/oauth-clients/*/secret:rotate")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/identity/platform/oauth-clients/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.PUT,
                    "/api/v1/identity/users/*",
                    "/api/v1/identity/users/*/roles",
                    "/api/v1/identity/role-permissions/*",
                    "/api/v1/identity/users/*/profile",
                    "/api/v1/identity/roles/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST, "/api/v1/identity/roles", "/api/v1/identity/roles/*:copy")
                .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/identity/roles/*")
                .authenticated()
                .anyRequest()
                .denyAll());
    http.exceptionHandling(
        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    http.oauth2ResourceServer(
        resource -> {
          resource.addObjectPostProcessor(
              new org.springframework.security.config.ObjectPostProcessor<
                  BearerTokenAuthenticationFilter>() {
                @Override
                public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                  filter.setAuthenticationFailureHandler(errors::commence);
                  return filter;
                }
              });
          resource
              .opaqueToken(token -> token.introspector(users::applicationPrincipal))
              .authenticationEntryPoint(errors)
              .accessDeniedHandler(errors)
              .bearerTokenResolver(
                  request -> {
                    if (Collections.list(request.getHeaders("Authorization")).isEmpty())
                      return null;
                    try {
                      return ResourceRequestGuard.bearer(request, "Authorization");
                    } catch (
                        org.springframework.security.oauth2.server.resource.introspection
                                .BadOpaqueTokenException
                            invalid) {
                      throw new org.springframework.security.oauth2.server.resource
                          .InvalidBearerTokenException("访问令牌无效");
                    }
                  });
        });
    http.addFilterAfter(
        new ResourceContextFilter(clock, parameters), BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
