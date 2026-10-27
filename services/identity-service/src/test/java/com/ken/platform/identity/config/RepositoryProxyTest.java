package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.mapper.authorization.AuthorizationMapper;
import com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository;
import com.ken.platform.identity.dal.mapper.oauth.UserTokenStore;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyStore;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用 Boot 同类异常转换后处理器验证数据仓库实际可装配，不关闭生产代理。 */
class RepositoryProxyTest {
  @Test
  void repositoriesCanBeClassProxiedAndKeepExceptionTranslation() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          PersistenceExceptionTranslationPostProcessor.class,
          () -> {
            var processor = new PersistenceExceptionTranslationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
          });
      var jdbc = mock(JdbcTemplate.class);
      var raw = new IllegalStateException("database-fixture");
      var translated =
          new org.springframework.dao.DataAccessResourceFailureException("translated-fixture");
      context.registerBean(
          PersistenceExceptionTranslator.class,
          () -> exception -> exception == raw ? translated : null);
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      var mapper = mock(AuthorizationMapper.class);
      context.registerBean(AuthorizationMapper.class, () -> mapper);
      context.register(
          AuthorizationRepository.class,
          UserTokenStore.class,
          SecurityPolicyStore.class,
          com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore.class);
      context.refresh();
      for (Class<?> type :
          new Class<?>[] {
            AuthorizationRepository.class,
            UserTokenStore.class,
            SecurityPolicyStore.class,
            com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore.class
          })
        assertThat(AopUtils.isCglibProxy(context.getBean(type))).as(type.getSimpleName()).isTrue();
      when(mapper.permissions()).thenThrow(raw);
      assertThatThrownBy(() -> context.getBean(AuthorizationRepository.class).permissions())
          .isSameAs(translated);
    }
  }
}
