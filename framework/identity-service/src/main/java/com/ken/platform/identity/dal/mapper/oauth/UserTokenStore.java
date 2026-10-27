package com.ken.platform.identity.dal.mapper.oauth;

import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 内省只从服务端已验证绑定选择租户，不读取客户端提交的主体或资料。 */
@Repository
public class UserTokenStore {
  public record Profile(String username, String name, String email) {}

  private final JdbcTemplate jdbc;

  public UserTokenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public <T> T scoped(UUID tenant, Supplier<T> action) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("用户 token 读取必须位于事务内");
    String prior =
        jdbc.queryForObject(
            "SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class);
    String next = tenant == null ? "" : tenant.toString();
    if (prior != null && !prior.isEmpty() && !prior.equals(next))
      throw new IllegalStateException("用户 token 事务不可切换租户");
    jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, next);
    try {
      return action.get();
    } finally {
      jdbc.queryForObject(
          "SELECT set_config('app.tenant_id',?,true)", String.class, prior == null ? "" : prior);
    }
  }

  public Profile profile(Identity identity) {
    return scoped(
        identity.tenant(),
        () ->
            jdbc
                .query(
                    identity.realm() == LoginRealm.PLATFORM
                        ? "SELECT normalized_username,display_name,NULL::text AS verified_email FROM sys_platform_user WHERE id=?"
                        : "SELECT normalized_username,display_name,CASE WHEN email_verified_at IS NOT NULL THEN verified_email END AS verified_email FROM sys_user WHERE id=?",
                    (row, index) ->
                        new Profile(
                            row.getString("normalized_username"),
                            row.getString("display_name"),
                            row.getString("verified_email")),
                    identity.user())
                .stream()
                .findFirst()
                .orElse(null));
  }

  public Set<String> capabilities(UUID tenant) {
    return scoped(
        tenant,
        () ->
            Set.copyOf(
                jdbc.queryForList(
                    "SELECT capability FROM sys_tenant_capability WHERE tenant_id=? AND enabled",
                    String.class,
                    tenant)));
  }

  public Map<String, String> serviceAudience(String target) {
    return jdbc
        .query(
            "SELECT owner_service,audience FROM sys_service_audience WHERE audience=? AND status='ACTIVE'",
            (row, index) -> Map.entry(row.getString(1), row.getString(2)),
            target)
        .stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
