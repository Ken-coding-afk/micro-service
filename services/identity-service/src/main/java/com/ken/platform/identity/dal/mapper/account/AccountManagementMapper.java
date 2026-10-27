package com.ken.platform.identity.dal.mapper.account;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountManagementMapper {
  record PlatformUser(UUID id, String status, boolean mustChangePassword) {}

  record Request(String digest, String result) {}

  @Select(
      "SELECT id,status,must_change_password AS \"mustChangePassword\" FROM sys_platform_user WHERE id=#{actor} FOR UPDATE")
  PlatformUser lockPlatformUser(@Param("actor") UUID actor);

  // 调用者先持有目录状态行锁；记录仅追加，普通读取不额外要求 UPDATE 权限。
  @Select(
      """
      SELECT request_digest AS digest,result_data::text AS result FROM identity_technical.management_request
      WHERE actor_type=#{type} AND actor_id=#{actor} AND tenant_id IS NOT DISTINCT FROM #{tenant}
        AND action=#{action} AND request_id=#{request}
      """)
  Request prior(
      @Param("type") String type,
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("action") String action,
      @Param("request") String request);

  @Insert(
      """
      INSERT INTO identity_technical.management_request(actor_type,actor_id,tenant_id,action,request_id,request_digest,result_data)
      VALUES(#{type},#{actor},#{tenant},#{action},#{request},#{digest},CAST(#{result} AS jsonb))
      """)
  void remember(
      @Param("type") String type,
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("action") String action,
      @Param("request") String request,
      @Param("digest") String digest,
      @Param("result") String result);

  @Insert(
      "INSERT INTO sys_tenant(id,code,name,expires_at) VALUES(#{id},#{code},#{name},#{expires})")
  void createTenant(
      @Param("id") UUID id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("expires") Instant expires);

  @Insert(
      """
      INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password)
      VALUES(#{id},#{tenant},#{username},#{name},#{hash},'PENDING_ACTIVATION',true)
      """)
  void createUser(
      @Param("id") UUID id,
      @Param("tenant") UUID tenant,
      @Param("username") String username,
      @Param("name") String name,
      @Param("hash") String hash);

  @Insert(
      """
      INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password)
      VALUES(#{id},#{username},#{name},#{hash},true)
      """)
  void createPlatformUser(
      @Param("id") UUID id,
      @Param("username") String username,
      @Param("name") String name,
      @Param("hash") String hash);

  @Select(
      """
      <script>
      SELECT count(*) FROM sys_permission WHERE owner_service='identity-service' AND principal_type='TENANT'
        AND status='ACTIVE' AND supported_data_scopes=ARRAY['TENANT']::text[]
        AND code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      </script>
      """)
  int activeCorePermissions(@Param("codes") List<String> codes);

  @Insert(
      "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(#{role},#{tenant},'tenant-admin','租户管理员',true)")
  void createProtectedRole(@Param("role") UUID role, @Param("tenant") UUID tenant);

  @Insert(
      """
      <script>
      INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope)
      SELECT #{tenant},#{role},code,'TENANT' FROM sys_permission
      WHERE owner_service='identity-service' AND principal_type='TENANT' AND status='ACTIVE'
        AND supported_data_scopes=ARRAY['TENANT']::text[]
        AND code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      </script>
      """)
  int grantCorePermissions(
      @Param("role") UUID role, @Param("tenant") UUID tenant, @Param("codes") List<String> codes);

  @Insert("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(#{tenant},#{user},#{role})")
  void initialMembership(
      @Param("tenant") UUID tenant, @Param("user") UUID user, @Param("role") UUID role);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},#{type},#{tenant},#{action},#{target},'SUCCESS',#{request},CAST(#{details} AS jsonb))
      """)
  void audit(
      @Param("actor") UUID actor,
      @Param("type") String type,
      @Param("tenant") UUID tenant,
      @Param("action") String action,
      @Param("target") String target,
      @Param("request") String request,
      @Param("details") String details);
}
