package com.ken.platform.identity.dal.support;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 登录前仅接受服务端解析的租户；与调用方共享事务，不能独立提交。 */
@Component
public final class IdentityTenantScope {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate mandatory;

  public IdentityTenantScope(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    mandatory = new TransactionTemplate(manager);
    mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }

  public <T> T within(UUID tenant, Supplier<T> operation) {
    return mandatory.execute(
        status -> {
          String previous =
              jdbc.queryForObject(
                  "SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class);
          String selected = tenant == null ? "" : tenant.toString();
          if (!previous.isEmpty() && !previous.equals(selected))
            throw new IllegalStateException("身份事务不能切换租户");
          jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, selected);
          T result = operation.get();
          // 失败由参与事务回滚；不能在已失败 SQL 事务中执行恢复并覆盖原异常。
          jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, previous);
          return result;
        });
  }
}
