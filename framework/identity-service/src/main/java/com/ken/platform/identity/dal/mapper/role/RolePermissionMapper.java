package com.ken.platform.identity.dal.mapper.role;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RolePermissionMapper {
  record RoleRow(
      UUID id, String status, boolean protectedRole, long version, long authorizationVersion) {}

  record GrantRow(String permissionCode, String dataScope) {}

  record PermissionRow(String code, String principalType, String status, String scopes) {}

  @Select("SELECT role_id FROM sys_user_role WHERE tenant_id=#{tenant} AND user_id=#{user}")
  List<UUID> memberRoles(@Param("tenant") UUID tenant, @Param("user") UUID user);

  @Select(
      """
      <script>
      SELECT id,status,protected AS "protectedRole",version,authorization_version AS "authorizationVersion"
      FROM sys_role WHERE tenant_id=#{tenant} AND id IN
      <foreach item="role" collection="roles" open="(" separator="," close=")">#{role}</foreach>
      ORDER BY id FOR UPDATE
      </script>
      """)
  List<RoleRow> lockRoles(@Param("tenant") UUID tenant, @Param("roles") List<UUID> roles);

  @Select(
      """
      SELECT permission_code AS "permissionCode",data_scope AS "dataScope" FROM sys_role_permission
      WHERE tenant_id=#{tenant} AND role_id=#{role} ORDER BY permission_code
      """)
  List<GrantRow> grants(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Select(
      "SELECT code,principal_type AS \"principalType\",status,array_to_string(supported_data_scopes,',') AS scopes FROM sys_permission ORDER BY code")
  List<PermissionRow> catalog();

  @Delete(
      "DELETE FROM sys_role_permission WHERE tenant_id=#{tenant} AND role_id=#{role} AND permission_code=#{permission}")
  void removeGrant(
      @Param("tenant") UUID tenant,
      @Param("role") UUID role,
      @Param("permission") String permission);

  @Insert(
      """
      INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope)
      VALUES(#{tenant},#{role},#{permission},#{scope,jdbcType=VARCHAR})
      """)
  void addGrant(
      @Param("tenant") UUID tenant,
      @Param("role") UUID role,
      @Param("permission") String permission,
      @Param("scope") String scope);

  @Update(
      """
      UPDATE sys_role SET version=version+1,authorization_version=authorization_version+1,updated_at=now()
      WHERE tenant_id=#{tenant} AND id=#{role} AND version=#{version}
      """)
  int touchAuthorization(
      @Param("tenant") UUID tenant, @Param("role") UUID role, @Param("version") long version);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},'TENANT',#{tenant},'identity.role.permissions',#{target},'SUCCESS',#{request},CAST(#{details} AS jsonb))
      """)
  void audit(
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("target") String target,
      @Param("request") String request,
      @Param("details") String details);
}
