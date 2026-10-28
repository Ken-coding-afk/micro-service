package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.time.Duration;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.RedisSessionMapper;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration(proxyBeanMethods = false)
@EnableSpringHttpSession
public class IdentitySessionConfiguration {
  public static JacksonJsonRedisSerializer<Object> serializer() {
    var validator =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(Identity.class)
            .allowIfSubType(LoginRealm.class)
            .allowIfSubType(java.util.UUID.class)
            .allowIfSubType(Long.class)
            .allowIfSubType(Integer.class);
    var mapper =
        JsonMapper.builder()
            .addModules(
                SecurityJacksonModules.getModules(
                    IdentitySessionConfiguration.class.getClassLoader(), validator))
            .build();
    return new JacksonJsonRedisSerializer<>(mapper, Object.class);
  }

  @Bean
  RedisSessionRepository sessionRepository(
      RedisConnectionFactory identityRedisConnectionFactory, Environment environment) {
    String project = environment.getRequiredProperty("PROJECT_CODE"),
        app = environment.getRequiredProperty("APP_ENV");
    if (!project.matches("[a-z][a-z0-9-]{0,63}") || !Set.of("local", "test", "prod").contains(app))
      throw new IllegalArgumentException("SSO 缺少受验证的项目/环境");
    var template = new RedisTemplate<String, Object>();
    template.setConnectionFactory(identityRedisConnectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(serializer());
    template.setHashValueSerializer(serializer());
    template.afterPropertiesSet();
    var repository = new RedisSessionRepository(template);
    repository.setRedisKeyNamespace(project + ":" + app + ":identity-service:global:sso");
    repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
    var mapper = new RedisSessionMapper();
    repository.setRedisSessionMapper(
        (sessionId, attributes) -> {
          try {
            return mapper.apply(sessionId, attributes);
          } catch (IllegalStateException incomplete) {
            // 删除/过期与迟到 HSET 竞争可能只留下部分字段；无效记录不能恢复为登录会话。
            repository.deleteById(sessionId);
            return null;
          }
        });
    return repository;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<
          org.springframework.session.web.http.SessionRepositoryFilter<?>>
      ssoFilterRegistration(
          org.springframework.session.web.http.SessionRepositoryFilter<?>
              springSessionRepositoryFilter) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<
            org.springframework.session.web.http.SessionRepositoryFilter<?>>(
            springSessionRepositoryFilter);
    registration.setOrder(
        org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER);
    // MVC 的 FlashMap 也可能读取既有 session；Bearer API 不进入 SSO 包装器，避免续写 Cookie。
    registration.addUrlPatterns(
        "/identity/login",
        "/identity/password/initial",
        "/identity/session",
        "/identity/consent",
        "/identity/logout",
        "/identity/external",
        "/identity/external/*",
        "/oauth2/authorize",
        "/connect/logout",
        "/oauth2/authorization/*",
        "/login/oauth2/*");
    registration.setDispatcherTypes(
        jakarta.servlet.DispatcherType.REQUEST,
        jakarta.servlet.DispatcherType.ASYNC,
        jakarta.servlet.DispatcherType.ERROR);
    return registration;
  }

  @Bean
  CookieSerializer cookieSerializer(AuthorizationServerSettings settings) {
    var cookie = new DefaultCookieSerializer();
    cookie.setCookieName("IDENTITY_SSO");
    cookie.setCookiePath("/");
    cookie.setUseHttpOnlyCookie(true);
    cookie.setSameSite("Lax");
    cookie.setUseSecureCookie(settings.getIssuer().startsWith("https://"));
    cookie.setUseBase64Encoding(false);
    return cookie;
  }
}
