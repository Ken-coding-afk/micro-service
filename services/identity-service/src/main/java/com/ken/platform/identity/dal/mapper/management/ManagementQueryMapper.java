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
      long version,
      String realName,
      String employeeNo,
      String contactEmail,
      String phone,
      String avatarUrl,
      String description,
      java.time.Instant createdAt,
      java.time.Instant updatedAt,
      java.time.Instant lastLoginAt,
      String groupNames) {}

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
      "u.id,u.normalized_username AS username,u.display_name AS \"displayName\",u.status,u.must_change_password AS \"mustChangePassword\",u.version,u.real_name AS \"realName\",u.employee_no AS \"employeeNo\",u.contact_email AS \"contactEmail\",u.phone,u.avatar_url AS \"avatarUrl\",u.description,u.created_at AS \"createdAt\",u.updated_at AS \"updatedAt\",u.last_login_at AS \"lastLoginAt\",(SELECT string_agg(g.name,'、' ORDER BY g.sort,g.name) FROM sys_user_group_member m JOIN sys_user_group g ON g.tenant_id=m.tenant_id AND g.id=m.group_id WHERE m.tenant_id=u.tenant_id AND m.user_id=u.id) AS \"groupNames\"";
  String ROLE_COLUMNS =
      "r.id,r.code,r.name,r.status,r.protected AS \"protectedRole\",r.version,r.authorization_version AS \"authorizationVersion\",r.description";

  @Select(
      "SELECT "
          + USER_COLUMNS
          + " FROM sys_user u WHERE u.tenant_id=#{tenant} "
          + "AND (#{query}='' OR position(lower(#{query}) in lower(u.normalized_username || ' ' || u.display_name || ' ' || coalesce(u.real_name,'') || ' ' || coalesce(u.employee_no,'')))>0) "
          + "AND (#{status}='' OR u.status=#{status}) "
          + "AND (CAST(#{group} AS uuid) IS NULL OR EXISTS (WITH RECURSIVE subtree AS (SELECT id FROM sys_user_group WHERE tenant_id=#{tenant} AND id=#{group} UNION ALL SELECT g.id FROM sys_user_group g JOIN subtree p ON g.parent_id=p.id WHERE g.tenant_id=#{tenant} AND #{descendants}) SELECT 1 FROM sys_user_group_member m JOIN subtree g ON g.id=m.group_id WHERE m.tenant_id=u.tenant_id AND m.user_id=u.id)) "
          + "ORDER BY u.normalized_username,u.id LIMIT #{limit} OFFSET #{offset}")
  List<UserRow> users(
      @Param("tenant") UUID tenant,
      @Param("status") String status,
      @Param("group") UUID group,
      @Param("descendants") boolean descendants,
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
