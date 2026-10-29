package com.ken.platform.identity.dal.mapper.permission;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PermissionCatalogMapper {
  record Row(
      String code,
      String name,
      String ownerService,
      String principalType,
      String groupCode,
      String groupName,
      String resourceCode,
      String resourceName,
      String actionCode,
      String description,
      int sort,
      String scopes,
      String oauthScope,
      String status,
      String source) {}

  @Select("SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR UPDATE")
  long lockVersion();

  @Select(
      """
      SELECT code,name,owner_service AS "ownerService",principal_type AS "principalType",
        group_code AS "groupCode",group_name AS "groupName",resource_code AS "resourceCode",
        resource_name AS "resourceName",action_code AS "actionCode",description,sort,
        array_to_string(supported_data_scopes,',') AS scopes,oauth_scope AS "oauthScope",status,source
      FROM sys_permission ORDER BY code
      """)
  List<Row> all();

  @Insert(
      """
      INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,
        resource_code,resource_name,action_code,description,sort,supported_data_scopes,oauth_scope,status,source)
      VALUES(#{code},#{name},#{ownerService},#{principalType},#{groupCode},#{groupName},
        #{resourceCode},#{resourceName},#{actionCode},#{description},#{sort},string_to_array(#{scopes},','),#{oauthScope},#{status},#{source})
      ON CONFLICT(code) DO UPDATE SET name=excluded.name,group_code=excluded.group_code,
        group_name=excluded.group_name,resource_name=excluded.resource_name,description=excluded.description,
        sort=excluded.sort,status=excluded.status,updated_at=now()
      """)
  void save(Row value);

  @Update(
      "UPDATE sys_permission_catalog_state SET version=version+1 WHERE id=1 AND version=#{version}")
  int advance(@Param("version") long version);

  @Insert(
      """
      INSERT INTO sys_auth_audit(actor_type,action,target_id,result,request_id,details)
      VALUES('SERVICE','identity.permissions.published',#{service},'SUCCESS',#{request},CAST(#{details} AS jsonb))
      """)
  void audit(
      @Param("service") String service,
      @Param("request") String request,
      @Param("details") String details);
}
