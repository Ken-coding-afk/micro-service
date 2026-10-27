package com.ken.platform.identity.config;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.security.ResourceContextFilter;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.oauth.PublisherTokens;
import java.time.Clock;
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

@Configuration(proxyBeanMethods = false)
public class CatalogResourceConfiguration {
  @Bean
  @Order(2)
  SecurityFilterChain catalogResource(
      HttpSecurity http,
      PublisherTokens tokens,
      Clock clock,
      RuntimeParameters parameters,
      ManagementAudits audits)
      throws Exception {
    var errors = new IdentityResourceErrors(audits);
    http.securityMatcher(
        "/internal/v1/permission-catalogs/**", "/internal/v1/permission-catalogs:sync");
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
                .requestMatchers(HttpMethod.GET, "/internal/v1/permission-catalogs/version")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/internal/v1/permission-catalogs:sync")
                .authenticated()
                .anyRequest()
                .denyAll());
    http.oauth2ResourceServer(
        resource ->
            resource
                .opaqueToken(token -> token.introspector(tokens::principal))
                .authenticationEntryPoint(errors)
                .accessDeniedHandler(errors));
    http.addFilterAfter(
        new ResourceContextFilter(clock, parameters), BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
