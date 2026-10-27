package com.ken.platform.identity.dal.mapper.role;

import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoleAssignmentMapper {
  @Delete(
      "DELETE FROM sys_user_role WHERE tenant_id=#{tenant} AND user_id=#{user} AND role_id=#{role}")
  void remove(@Param("tenant") UUID tenant, @Param("user") UUID user, @Param("role") UUID role);

  @Insert("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES (#{tenant},#{user},#{role})")
  void add(@Param("tenant") UUID tenant, @Param("user") UUID user, @Param("role") UUID role);

  @Update(
      "UPDATE sys_role SET version=version+1,updated_at=now() WHERE tenant_id=#{tenant} AND id=#{role} AND version=#{version}")
  int touchRoleReference(
      @Param("tenant") UUID tenant, @Param("role") UUID role, @Param("version") long version);

  @Update(
      "UPDATE sys_user SET version=version+1,updated_at=now() WHERE tenant_id=#{tenant} AND id=#{user} AND version=#{version}")
  int touchUser(
      @Param("tenant") UUID tenant, @Param("user") UUID user, @Param("version") long version);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
      VALUES(#{actor},'TENANT',#{tenant},'identity.user.roles',#{target},'SUCCESS',#{request},CAST(#{details} AS jsonb))
      """)
  void audit(
      @Param("actor") UUID actor,
      @Param("tenant") UUID tenant,
      @Param("target") String target,
      @Param("request") String request,
      @Param("details") String details);
}
