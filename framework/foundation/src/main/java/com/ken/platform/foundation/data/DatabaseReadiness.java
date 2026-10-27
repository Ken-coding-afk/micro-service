package com.ken.platform.foundation.data;

import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;

/** 启动监听前、Nacos 注册前验证实际 PG 会话；不能凭环境账号名称假定最小权限。 */
public final class DatabaseReadiness implements SmartInitializingSingleton, PlatformReadinessCheck {
  private final JdbcTemplate jdbc;
  private final String user;
  private final String database;

  public DatabaseReadiness(JdbcTemplate jdbc, String user, String database) {
    this.jdbc = jdbc;
    this.user = user;
    this.database = database;
  }

  @Override
  public void afterSingletonsInstantiated() {
    verify();
  }

  @Override
  public void verify() {
    try {
      Boolean unsafe =
          jdbc.queryForObject(
              """
          SELECT current_user<>session_user OR current_user::text<>? OR current_database()::text<>?
            OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname=session_user
              AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication))
            OR has_database_privilege(current_user,current_database(),'CREATE')
            OR EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER'))
            OR EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
              AND (has_schema_privilege(current_user,n.oid,'CREATE') OR pg_has_role(current_user,n.nspowner,'MEMBER')))
            OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
                AND pg_has_role(current_user,c.relowner,'MEMBER'))
          """,
              Boolean.class, user, database);
      if (!Boolean.FALSE.equals(unsafe)) throw new IllegalStateException();
    } catch (RuntimeException failure) {
      throw new IllegalStateException("数据库连接或运行身份不满足最小权限合同，应用不能就绪");
    }
  }
}
