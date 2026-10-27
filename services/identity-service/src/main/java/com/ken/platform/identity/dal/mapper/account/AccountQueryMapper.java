package com.ken.platform.identity.dal.mapper.account;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 平台控制面最小只读字段，不读取租户用户、密码、token 或业务记录。 */
@Mapper
public interface AccountQueryMapper {
  record Administrator(
      UUID id,
      String username,
      String displayName,
      String status,
      boolean mustChangePassword,
      long version) {}

  record Tenant(
      UUID id, String code, String name, String status, Instant expiresAt, long version) {}

  String ADMIN_FIELDS =
      "id,normalized_username AS username,display_name AS \"displayName\",status,must_change_password AS \"mustChangePassword\",version";
  String TENANT_FIELDS = "id,code,name,status,expires_at AS \"expiresAt\",version";

  @Select(
      "SELECT "
          + ADMIN_FIELDS
          + " FROM sys_platform_user WHERE #{query}='' OR position(lower(#{query}) in lower(normalized_username || ' ' || display_name))>0 ORDER BY normalized_username,id LIMIT #{limit} OFFSET #{offset}")
  List<Administrator> administrators(
      @Param("query") String query, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT " + ADMIN_FIELDS + " FROM sys_platform_user WHERE id=#{id}")
  Administrator administrator(@Param("id") UUID id);

  @Select(
      "SELECT "
          + TENANT_FIELDS
          + " FROM sys_tenant WHERE #{query}='' OR position(lower(#{query}) in lower(code || ' ' || name))>0 ORDER BY code,id LIMIT #{limit} OFFSET #{offset}")
  List<Tenant> tenants(
      @Param("query") String query, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT " + TENANT_FIELDS + " FROM sys_tenant WHERE id=#{id}")
  Tenant tenant(@Param("id") UUID id);
}
