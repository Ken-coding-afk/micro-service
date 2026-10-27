package com.ken.platform.identity.dal.mapper.login;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理列表只读安全摘要；普通对象定义与用户管理共用，绝不读取 Redis ID/token。 */
@Mapper
public interface SessionManagementMapper {
  record Row(
      UUID id,
      UUID userId,
      String username,
      String displayName,
      Instant createdAt,
      Instant lastActivityAt,
      Instant revokedAt,
      boolean restricted,
      String userStatus,
      boolean mustChangePassword,
      long authVersion,
      long userAuthVersion,
      Long tenantAuthVersion,
      long currentTenantAuthVersion) {}

  @Select(
      """
      SELECT s.id,s.user_id AS "userId",u.normalized_username AS username,u.display_name AS "displayName",
        s.created_at AS "createdAt",s.last_activity_at AS "lastActivityAt",s.revoked_at AS "revokedAt",
        s.restricted,u.status AS "userStatus",u.must_change_password AS "mustChangePassword",
        s.auth_version AS "authVersion",u.auth_version AS "userAuthVersion",
        s.tenant_auth_version AS "tenantAuthVersion",t.auth_version AS "currentTenantAuthVersion"
      FROM sys_login_session s JOIN sys_user u ON u.tenant_id=s.tenant_id AND u.id=s.user_id
        JOIN sys_tenant t ON t.id=s.tenant_id
      WHERE s.realm='TENANT' AND s.tenant_id=#{tenant}
        AND (CAST(#{target,jdbcType=OTHER} AS uuid) IS NULL OR s.user_id=#{target,jdbcType=OTHER})
      ORDER BY s.created_at DESC,s.id LIMIT #{limit} OFFSET #{offset}
      """)
  List<Row> list(
      @Param("tenant") UUID tenant,
      @Param("target") UUID target,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},'TENANT',#{tenant},#{action},#{target},'SUCCESS',#{request},
        jsonb_build_object('user_id',#{user}::text,'revoked',#{count}::integer))
      """)
  void audit(
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("action") String action,
      @Param("target") String target,
      @Param("user") UUID user,
      @Param("count") int count,
      @Param("request") String request);
}
