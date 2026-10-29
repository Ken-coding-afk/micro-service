package com.ken.platform.identity.dal.mapper.authorization;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** RLS 之外仍显式带租户条件；调用者必须先进入 TenantTransactions。 */
@Mapper
public interface AuthorizationMapper {
  record PermissionRow(
      String code,
      String ownerService,
      String oauthScope,
      String supportedDataScopes,
      String status) {}

  record GrantRow(
      UUID roleId,
      String permissionCode,
      String dataScope,
      boolean roleActive,
      long authorizationVersion) {}

  @Select(
      """
      SELECT code, owner_service AS "ownerService", oauth_scope AS "oauthScope",
             array_to_string(supported_data_scopes, ',') AS "supportedDataScopes", status
      FROM sys_permission WHERE principal_type = 'TENANT' ORDER BY code
      """)
  List<PermissionRow> permissions();

  @Select(
      """
      SELECT code,owner_service AS "ownerService",oauth_scope AS "oauthScope",
        array_to_string(supported_data_scopes, ',') AS "supportedDataScopes",status
      FROM sys_permission WHERE principal_type='PLATFORM' ORDER BY code
      """)
  List<PermissionRow> platformPermissions();

  @Select(
      """
      SELECT DISTINCT r.id AS "roleId", rp.permission_code AS "permissionCode", rp.data_scope AS "dataScope",
             r.status = 'ACTIVE' AS "roleActive", r.authorization_version AS "authorizationVersion"
      FROM sys_user u
      JOIN sys_tenant t ON t.id = u.tenant_id
      JOIN sys_user_role_source ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
      JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
      JOIN sys_role_permission rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.id
      WHERE u.tenant_id = #{tenantId} AND u.id = #{userId} AND u.status = 'ACTIVE'
        AND t.status = 'ACTIVE' AND (t.expires_at IS NULL OR t.expires_at > current_timestamp)
      ORDER BY rp.permission_code, r.id
      """)
  List<GrantRow> grants(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

  record Origin(UUID roleId, String sourceType, UUID groupId, String groupName, String groupPath) {}

  @Select(
      """
      SELECT role_id AS "roleId",source_type AS "sourceType",group_id AS "groupId",
        group_name AS "groupName",group_path AS "groupPath"
      FROM sys_user_role_source WHERE tenant_id=#{tenant} AND user_id=#{user}
      ORDER BY role_id,source_type,group_id
      """)
  List<Origin> origins(@Param("tenant") UUID tenant, @Param("user") UUID user);
}
