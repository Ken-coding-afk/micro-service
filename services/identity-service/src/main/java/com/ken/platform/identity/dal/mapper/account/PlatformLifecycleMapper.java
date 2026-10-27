package com.ken.platform.identity.dal.mapper.account;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformLifecycleMapper {
  record Administrator(
      UUID id,
      String name,
      String status,
      boolean mustChangePassword,
      long version,
      long authVersion) {}

  record Tenant(
      UUID id, String name, String status, Instant expiresAt, long version, long authVersion) {}

  @Select(
      """
      <script>
      SELECT id,display_name AS name,status,must_change_password AS "mustChangePassword",version,auth_version AS "authVersion"
      FROM sys_platform_user WHERE id IN
      <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY id FOR UPDATE
      </script>
      """)
  List<Administrator> lockAdministrators(@Param("ids") List<UUID> ids);

  @Select(
      "SELECT count(*) FROM sys_platform_user WHERE status='ACTIVE' AND NOT must_change_password")
  long effectiveAdministratorCount();

  @Update(
      """
      UPDATE sys_platform_user SET display_name=#{name},status=#{status},version=version+1,
        auth_version=auth_version+#{revoke},updated_at=now() WHERE id=#{id} AND version=#{version}
      """)
  int updateAdministrator(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("name") String name,
      @Param("status") String status,
      @Param("revoke") int revoke);

  @Select(
      """
      SELECT id,name,status,expires_at AS "expiresAt",version,auth_version AS "authVersion"
      FROM sys_tenant WHERE id=#{id} FOR UPDATE
      """)
  Tenant lockTenant(@Param("id") UUID id);

  @Update(
      """
      UPDATE sys_tenant SET name=#{name},expires_at=#{expires},status=#{status},version=version+1,
        auth_version=auth_version+#{revoke},updated_at=now() WHERE id=#{id} AND version=#{version}
      """)
  int updateTenant(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("name") String name,
      @Param("expires") Instant expires,
      @Param("status") String status,
      @Param("revoke") int revoke);
}
