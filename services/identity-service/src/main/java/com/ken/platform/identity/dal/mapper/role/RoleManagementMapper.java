package com.ken.platform.identity.dal.mapper.role;

import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoleManagementMapper {
  record RoleDetails(
      UUID id,
      String code,
      String name,
      String status,
      boolean protectedRole,
      long version,
      long authorizationVersion,
      String description) {}

  @Select(
      """
      SELECT id,code,name,status,protected AS "protectedRole",version,authorization_version AS "authorizationVersion",description
      FROM sys_role WHERE tenant_id=#{tenant} AND id=#{role}
      """)
  RoleDetails details(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Insert(
      "INSERT INTO sys_role(id,tenant_id,code,name,status,description) VALUES (#{id},#{tenant},#{code},#{name},#{status},#{description})")
  void create(
      @Param("id") UUID id,
      @Param("tenant") UUID tenant,
      @Param("code") String code,
      @Param("name") String name,
      @Param("status") String status,
      @Param("description") String description);

  @Update(
      """
      UPDATE sys_role SET name=#{name},status=#{status},description=#{description},version=version+1,
        authorization_version=authorization_version+CASE WHEN status <> #{status} THEN 1 ELSE 0 END,updated_at=now()
      WHERE tenant_id=#{tenant} AND id=#{role} AND version=#{version}
      """)
  int update(
      @Param("tenant") UUID tenant,
      @Param("role") UUID role,
      @Param("version") long version,
      @Param("name") String name,
      @Param("status") String status,
      @Param("description") String description);

  @Select(
      "SELECT EXISTS(SELECT 1 FROM sys_user_role WHERE tenant_id=#{tenant} AND role_id=#{role}) OR EXISTS(SELECT 1 FROM sys_user_group_role WHERE tenant_id=#{tenant} AND role_id=#{role})")
  boolean hasMembers(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Select(
      "SELECT EXISTS(SELECT 1 FROM sys_management_grant WHERE tenant_id=#{tenant} AND role_id=#{role})")
  boolean hasDelegationReferences(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Delete("DELETE FROM sys_role_permission WHERE tenant_id=#{tenant} AND role_id=#{role}")
  int clearPermissions(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Delete("DELETE FROM sys_role WHERE tenant_id=#{tenant} AND id=#{role} AND version=#{version}")
  int delete(
      @Param("tenant") UUID tenant, @Param("role") UUID role, @Param("version") long version);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},#{actorType},#{tenant},#{action},#{target},'SUCCESS',#{request},CAST(#{details} AS jsonb))
      """)
  void audit(
      @Param("actor") UUID actor,
      @Param("actorType") String actorType,
      @Param("tenant") UUID tenant,
      @Param("action") String action,
      @Param("target") String target,
      @Param("request") String request,
      @Param("details") String details);
}
