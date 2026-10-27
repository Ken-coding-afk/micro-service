package com.ken.platform.foundation.data;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 在同一 Spring 事务/连接上设置 LOCAL 租户；与业务变化、审计和 Outbox 一起提交。 */
public final class TenantTransactions {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public TenantTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
  }

  public <T> T execute(Supplier<T> operation) {
    CallContext context = CallContexts.current();
    if (context == null || context.tenantId() == null) {
      throw new PlatformException("TENANT_CONTEXT_REQUIRED", "缺少可信租户上下文", 403);
    }
    String tenant = context.tenantId().toString();
    return transactions.execute(
        status -> {
          String previous =
              jdbc.queryForObject(
                  "SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class);
          if (previous != null && !previous.isEmpty() && !previous.equals(tenant)) {
            throw new PlatformException("TENANT_CONTEXT_CONFLICT", "同一事务不能切换租户", 403);
          }
          jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
          T result = operation.get();
          // 正常嵌套返回时还原外层；异常由 Spring 回滚，避免在已中止的事务里掩盖原始错误。
          jdbc.queryForObject(
              "SELECT set_config('app.tenant_id', ?, true)",
              String.class,
              previous == null ? "" : previous);
          return result;
        });
  }
}
