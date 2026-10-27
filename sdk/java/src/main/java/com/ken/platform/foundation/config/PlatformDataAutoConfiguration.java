package com.ken.platform.foundation.config;

import com.ken.platform.foundation.data.TenantTransactions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
    })
@ConditionalOnClass(
    name = {
      "org.springframework.jdbc.core.JdbcTemplate",
      "org.springframework.transaction.PlatformTransactionManager"
    })
@ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
public class PlatformDataAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "platformDatabaseReadiness")
  com.ken.platform.foundation.data.DatabaseReadiness platformDatabaseReadiness(
      JdbcTemplate jdbc, org.springframework.core.env.Environment environment) {
    return new com.ken.platform.foundation.data.DatabaseReadiness(
        jdbc,
        environment.getRequiredProperty("DB_USER"),
        environment.getRequiredProperty("DB_NAME"));
  }

  @Bean
  @ConditionalOnMissingBean
  TenantTransactions platformTenantTransactions(
      JdbcTemplate jdbc, PlatformTransactionManager manager) {
    return new TenantTransactions(jdbc, manager);
  }
}
