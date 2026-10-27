package com.ken.platform.identity.dal.mapper.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserManagementMapper {
  @Update(
      "UPDATE sys_user SET auth_version=auth_version+1,version=version+1,updated_at=now() WHERE tenant_id=#{tenant} AND id=#{user} AND auth_version=#{authVersion}")
  int incrementAuthVersion(
      @Param("tenant") UUID tenant,
      @Param("user") UUID user,
      @Param("authVersion") long authVersion);

  record TenantRow(UUID id, String status, Instant expiresAt) {}

  record UserRow(
      UUID id, String status, boolean mustChangePassword, long version, long authVersion) {}

  @Select("SELECT display_name FROM sys_user WHERE tenant_id=#{tenant} AND id=#{user}")
  String displayName(@Param("tenant") UUID tenant, @Param("user") UUID user);

  @Update(
      "UPDATE sys_user SET display_name=#{name},version=version+1,updated_at=now() WHERE tenant_id=#{tenant} AND id=#{user} AND version=#{version}")
  int updateDisplayName(
      @Param("tenant") UUID tenant,
      @Param("user") UUID user,
      @Param("version") long version,
      @Param("name") String name);

  @Insert(
      "INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details) VALUES(#{actor},'TENANT',#{tenant},'identity.user.profile.updated',#{target},'SUCCESS',#{request},CAST(#{details} AS jsonb))")
  void auditProfile(
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("target") String target,
      @Param("request") String request,
      @Param("details") String details);

  @Select("SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR UPDATE")
  long lockCatalog();

  @Select(
      "SELECT id,status,expires_at AS \"expiresAt\" FROM sys_tenant WHERE id=#{tenant} FOR UPDATE")
  TenantRow lockTenant(@Param("tenant") UUID tenant);

  @Select(
      """
      <script>
      SELECT r.id FROM sys_role r WHERE r.tenant_id=#{tenant} AND EXISTS (
        SELECT 1 FROM sys_user_role ur WHERE ur.tenant_id=r.tenant_id AND ur.role_id=r.id AND ur.user_id IN
        <foreach item="user" collection="users" open="(" separator="," close=")">#{user}</foreach>
      ) ORDER BY r.id FOR UPDATE
      </script>
      """)
  List<String> lockMemberRoles(@Param("tenant") UUID tenant, @Param("users") List<UUID> users);

  @Select(
      """
      <script>
      SELECT id,status,must_change_password AS "mustChangePassword",version,auth_version AS "authVersion"
      FROM sys_user WHERE tenant_id=#{tenant} AND id IN
      <foreach item="user" collection="users" open="(" separator="," close=")">#{user}</foreach>
      ORDER BY id FOR UPDATE
      </script>
      """)
  List<UserRow> lockUsers(@Param("tenant") UUID tenant, @Param("users") List<UUID> users);

  @Select(
      """
      SELECT EXISTS (
        SELECT 1 FROM sys_user_role ur
        JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
        JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id
        JOIN sys_permission p ON p.code=rp.permission_code
        WHERE ur.tenant_id=#{tenant} AND ur.user_id=#{user}
          AND r.protected AND r.status='ACTIVE' AND p.principal_type='TENANT'
          AND p.status IN ('ACTIVE','DEPRECATED') AND rp.permission_code=#{operation} AND rp.data_scope='TENANT'
      )
      """)
  boolean protectedAdminCan(
      @Param("tenant") UUID tenant, @Param("user") UUID user, @Param("operation") String operation);

  @Select(
      """
      SELECT EXISTS(SELECT 1 FROM sys_user_role ur
        JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
        JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id
        JOIN sys_permission p ON p.code=rp.permission_code
        WHERE ur.tenant_id=#{tenant} AND ur.user_id=#{user} AND r.status='ACTIVE'
          AND p.principal_type='TENANT' AND p.status IN ('ACTIVE','DEPRECATED')
          AND rp.permission_code=#{operation} AND rp.data_scope='TENANT')
      """)
  boolean memberCan(
      @Param("tenant") UUID tenant, @Param("user") UUID user, @Param("operation") String operation);

  @Select(
      """
      SELECT EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r
        ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
        WHERE ur.tenant_id=#{tenant} AND ur.user_id=#{user} AND r.protected AND r.status='ACTIVE')
      """)
  boolean hasProtectedRole(@Param("tenant") UUID tenant, @Param("user") UUID user);

  @Select(
      """
      SELECT count(DISTINCT u.id) FROM sys_user u
      JOIN sys_user_role ur ON ur.tenant_id=u.tenant_id AND ur.user_id=u.id
      JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
      WHERE u.tenant_id=#{tenant} AND u.status='ACTIVE' AND NOT u.must_change_password
        AND r.protected AND r.status='ACTIVE'
      """)
  long countEffectiveAdministrators(@Param("tenant") UUID tenant);

  @Update(
      """
      UPDATE sys_user SET status=#{status},version=version+1,auth_version=auth_version+1,updated_at=now()
      WHERE tenant_id=#{tenant} AND id=#{user} AND version=#{version}
      """)
  int setStatus(
      @Param("tenant") UUID tenant,
      @Param("user") UUID user,
      @Param("status") String status,
      @Param("version") long version);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},'TENANT',#{tenant},'identity.user.status',#{target},'SUCCESS',#{requestId},CAST(#{details} AS jsonb))
      """)
  void auditStatus(
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("target") String target,
      @Param("requestId") String requestId,
      @Param("details") String details);

  @Insert(
      """
      INSERT INTO identity_technical.outbox(event_id,event_type,schema_version,tenant_id,producer,
        aggregate_id,aggregate_version,occurred_at,traceparent,data)
      VALUES(#{event},#{type},1,#{tenant},'identity-service',#{user},#{version},#{occurredAt},#{trace},CAST(#{data} AS jsonb))
      """)
  void statusEvent(
      @Param("event") UUID event,
      @Param("type") String type,
      @Param("tenant") UUID tenant,
      @Param("user") UUID user,
      @Param("version") long version,
      @Param("occurredAt") Instant occurredAt,
      @Param("trace") String trace,
      @Param("data") String data);
}
