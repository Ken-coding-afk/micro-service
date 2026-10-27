package com.ken.platform.identity.dal.mapper.authorization;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.service.authorization.AuthorizationEvaluator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class AuthorizationPersistenceTest {
  @Test
  void mybatisSharesSpringTenantTransactionAndNeverLoadsOtherTenantRoles() throws Exception {
    var binding =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(binding.get("test_only").asBoolean()).isTrue();
    assertThat(binding.get("app_env").asString()).isEqualTo("local");
    assertThat(binding.get("dbname").asString()).startsWith("t02t");
    var configuration = new HikariConfig();
    configuration.setJdbcUrl(
        "jdbc:postgresql://"
            + binding.get("host").asString()
            + ":"
            + binding.get("port").asInt()
            + "/"
            + binding.get("dbname").asString());
    configuration.setUsername(binding.get("username").asString());
    configuration.setPassword(
        Files.readString(Path.of(binding.get("password_file").asString())).strip());
    configuration.setMaximumPoolSize(1);
    try (var pool = new HikariDataSource(configuration)) {
      var jdbc = new JdbcTemplate(pool);
      var manager = new JdbcTransactionManager(pool);
      var tenants = new TenantTransactions(jdbc, manager);
      var mybatis = new Configuration();
      mybatis
          .getTypeHandlerRegistry()
          .register(UUID.class, com.ken.platform.foundation.data.UuidTypeHandler.class);
      mybatis.addMapper(AuthorizationMapper.class);
      var factory = new SqlSessionFactoryBean();
      factory.setDataSource(pool);
      factory.setConfiguration(mybatis);
      var repository =
          new AuthorizationRepository(
              new SqlSessionTemplate(factory.getObject()).getMapper(AuthorizationMapper.class));
      UUID tenantA = UUID.fromString(binding.get("tenant_a").asString());
      UUID userA = UUID.fromString(binding.get("user_a").asString());
      UUID tenantB = UUID.fromString(binding.get("tenant_b").asString());
      UUID userB = UUID.fromString(binding.get("user_b").asString());
      try (var ignored =
          CallContexts.bind(
              new CallContext("test-authorization", tenantA, userA.toString(), null, null, null))) {
        new TransactionTemplate(manager)
            .execute(
                status -> {
                  status.setRollbackOnly();
                  return tenants.execute(
                      () -> {
                        UUID role = jdbc.queryForObject("SELECT id FROM sys_role", UUID.class);
                        jdbc.update(
                            "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (?,?,?)",
                            tenantA,
                            userA,
                            role);
                        var grants = repository.grants(tenantA, userA);
                        assertThat(grants).hasSize(1);
                        var evaluated =
                            new AuthorizationEvaluator(Clock.systemUTC())
                                .evaluate(repository.permissions(), grants, null);
                        assertThat(evaluated.permissions()).containsExactly("document:read");
                        assertThat(evaluated.dataScopes())
                            .containsEntry("document:read", DataScope.SELF);
                        assertThat(repository.grants(tenantB, userB)).isEmpty();
                        return true;
                      });
                });
      }
      assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user_role", Integer.class)).isZero();
    }
  }
}
