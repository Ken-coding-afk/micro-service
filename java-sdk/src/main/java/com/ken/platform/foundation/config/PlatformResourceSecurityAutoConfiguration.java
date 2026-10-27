package com.ken.platform.foundation.config;

import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.security.ResourceContextFilter;
import com.ken.platform.foundation.security.ResourceErrors;
import com.ken.platform.foundation.security.ResourceIdentitySettings;
import com.ken.platform.foundation.security.ResourceIntrospector;
import com.ken.platform.foundation.security.ResourceRequestGuard;
import java.time.Clock;
import java.util.Collections;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 普通 Servlet API 资源服务的固定安全装配；身份服务/Gateway 保留各自协议链。 */
@AutoConfiguration(
    after = {PlatformAutoConfiguration.class, PlatformNacosAutoConfiguration.class},
    beforeName = {
      "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
    })
@ConditionalOnClass(
    name = {
      "org.springframework.security.config.annotation.web.builders.HttpSecurity",
      "org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector",
      "org.springframework.web.servlet.HandlerInterceptor"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(NacosSettings.class)
@Conditional(PlatformResourceSecurityAutoConfiguration.ResourceApi.class)
public class PlatformResourceSecurityAutoConfiguration {
  public static class ResourceApi implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String service = context.getEnvironment().getProperty("SERVICE_NAME");
      return service != null
          && !Set.of("identity-service", "api-gateway").contains(service)
          && context.getEnvironment().getProperty("ROLE", "api").equals("api");
    }
  }

  @Bean
  ResourceIdentitySettings platformResourceIdentitySettings(Environment environment) {
    return ResourceIdentitySettings.from(environment);
  }

  @Bean(destroyMethod = "close")
  ResourceIntrospector platformResourceIntrospector(
      ServiceDiscovery discovery,
      ResourceIdentitySettings settings,
      RuntimeParameters parameters,
      Clock clock) {
    return new ResourceIntrospector(discovery, settings, parameters, clock);
  }

  @Bean
  ResourceRequestGuard platformResourceRequestGuard(ResourceIntrospector introspector) {
    return new ResourceRequestGuard(introspector);
  }

  @Bean
  @ConditionalOnBean(com.ken.platform.foundation.discovery.NacosRuntime.class)
  com.ken.platform.foundation.discovery.PlatformReadinessCheck platformResourceIdentityReadiness(
      com.ken.platform.foundation.discovery.NacosRuntime runtime,
      ResourceIntrospector introspector) {
    return () -> {
      try {
        runtime.watch("identity-service");
        introspector.verifyIdentity();
      } catch (Exception unavailable) {
        throw new IllegalStateException("资源内省身份检查失败，应用不能就绪");
      }
    };
  }

  @Bean
  WebMvcConfigurer platformResourceRoutes(ResourceRequestGuard guard) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/**", "/internal/**");
      }
    };
  }

  @Bean
  SecurityFilterChain platformResourceSecurity(
      HttpSecurity http,
      ResourceIntrospector introspector,
      Clock clock,
      RuntimeParameters parameters)
      throws Exception {
    var errors = new ResourceErrors();
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable);
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.securityContext(
        context -> context.securityContextRepository(new NullSecurityContextRepository()));
    http.requestCache(AbstractHttpConfigurer::disable);
    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness")
                .permitAll()
                .requestMatchers("/api/**", "/internal/**")
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
                  // Security 默认失败处理器重抛 AuthenticationServiceException，绕过 entry point。
                  filter.setAuthenticationFailureHandler(errors::commence);
                  return filter;
                }
              });
          resource
              .opaqueToken(token -> token.introspector(introspector))
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
    // 不能作为容器 Filter bean 注册，否则会在 Bearer 认证之前抢先设置 OncePerRequest 标记。
    http.addFilterAfter(
        new ResourceContextFilter(clock, parameters), BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
