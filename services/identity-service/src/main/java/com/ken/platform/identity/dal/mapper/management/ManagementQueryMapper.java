package com.ken.platform.identity.dal.mapper.management;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只选管理视图所需字段；不读取密码、验证渠道、token 或身份会话。 */
@Mapper
public interface ManagementQueryMapper {
  record UserRow(
      UUID id,
      String username,
      String displayName,
      String status,
      boolean mustChangePassword,
      long version) {}

  record RoleRow(
      UUID id,
      String code,
      String name,
      String status,
      boolean protectedRole,
      long version,
      long authorizationVersion,
      String description) {}

  record PermissionRow(
      String code,
      String name,
      String ownerService,
      String groupCode,
      String groupName,
      String resourceCode,
      String resourceName,
      String actionCode,
      String description,
      int sort,
      String status,
      String oauthScope,
      String scopes) {}

  String USER_COLUMNS =
      "u.id,u.normalized_username AS username,u.display_name AS \"displayName\",u.status,u.must_change_password AS \"mustChangePassword\",u.version";
  String ROLE_COLUMNS =
      "r.id,r.code,r.name,r.status,r.protected AS \"protectedRole\",r.version,r.authorization_version AS \"authorizationVersion\",r.description";

  @Select(
      "SELECT "
          + USER_COLUMNS
          + " FROM sys_user u WHERE u.tenant_id=#{tenant} "
          + "AND (#{query}='' OR position(lower(#{query}) in lower(u.normalized_username || ' ' || u.display_name))>0) "
          + "ORDER BY u.normalized_username,u.id LIMIT #{limit} OFFSET #{offset}")
  List<UserRow> users(
      @Param("tenant") UUID tenant,
      @Param("query") String query,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      "SELECT " + USER_COLUMNS + " FROM sys_user u WHERE u.tenant_id=#{tenant} AND u.id=#{user}")
  UserRow user(@Param("tenant") UUID tenant, @Param("user") UUID user);

  @Select(
      "SELECT "
          + USER_COLUMNS
          + " FROM sys_user u JOIN sys_user_role membership ON membership.tenant_id=u.tenant_id AND membership.user_id=u.id "
          + "WHERE u.tenant_id=#{tenant} AND membership.role_id=#{role} "
          + "ORDER BY u.normalized_username,u.id LIMIT #{limit} OFFSET #{offset}")
  List<UserRow> roleMembers(
      @Param("tenant") UUID tenant,
      @Param("role") UUID role,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      "SELECT "
          + ROLE_COLUMNS
          + " FROM sys_role r WHERE r.tenant_id=#{tenant} "
          + "AND (#{query}='' OR position(lower(#{query}) in lower(r.code || ' ' || r.name))>0) "
          + "ORDER BY r.code,r.id LIMIT #{limit} OFFSET #{offset}")
  List<RoleRow> roles(
      @Param("tenant") UUID tenant,
      @Param("query") String query,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      "SELECT " + ROLE_COLUMNS + " FROM sys_role r WHERE r.tenant_id=#{tenant} AND r.id=#{role}")
  RoleRow role(@Param("tenant") UUID tenant, @Param("role") UUID role);

  @Select(
      """
      SELECT code,name,owner_service AS "ownerService",group_code AS "groupCode",group_name AS "groupName",
        resource_code AS "resourceCode",resource_name AS "resourceName",action_code AS "actionCode",description,sort,status,
        oauth_scope AS "oauthScope",array_to_string(supported_data_scopes,',') AS scopes
      FROM sys_permission WHERE principal_type='TENANT' ORDER BY sort,code
      """)
  List<PermissionRow> permissions();
}
