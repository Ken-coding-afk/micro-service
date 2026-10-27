package com.ken.platform.identity.dal.mapper.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只供维护用例读取/追加的初始化账本；没有删除、覆盖或凭据查询方法。 */
public final class BootstrapStore {
  public record State(String project, String environment, String origin, UUID adminId) {}

  public record Audience(String audience, String status) {}

  private final JdbcTemplate jdbc;

  public BootstrapStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public State state() {
    return jdbc
        .query(
            "SELECT project_code,app_env,public_origin,initial_admin_id FROM sys_identity_bootstrap_state WHERE id=1",
            (row, index) ->
                new State(
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getObject(4, UUID.class)))
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void lockCatalog() {
    jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR UPDATE", Long.class);
  }

  public long platformUserCount() {
    return jdbc.queryForObject("SELECT count(*) FROM sys_platform_user", Long.class);
  }

  public void remember(String project, String environment, String origin, UUID admin) {
    jdbc.update(
        "INSERT INTO sys_identity_bootstrap_state(id,project_code,app_env,public_origin,initial_admin_id) VALUES(1,?,?,?,?)",
        project,
        environment,
        origin,
        admin);
  }

  public List<Audience> identityAudience() {
    return jdbc.query(
        "SELECT audience,status FROM sys_service_audience WHERE owner_service='identity-service' OR audience='identity-service'",
        (row, index) -> new Audience(row.getString(1), row.getString(2)));
  }

  public void createIdentityAudience() {
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES('identity-service','identity-service')");
  }
}
