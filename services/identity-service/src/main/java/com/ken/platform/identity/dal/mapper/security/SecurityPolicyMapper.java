package com.ken.platform.identity.dal.mapper.security;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SecurityPolicyMapper {
  record Row(
      String ownerType,
      UUID ownerTenantId,
      Integer passwordMinChars,
      Integer loginFailureThreshold,
      Integer loginFailureWindowSeconds,
      Integer loginLockSeconds,
      Integer loginIpAttemptLimit,
      Integer loginIpWindowSeconds,
      Integer sessionAbsoluteSeconds,
      Integer sessionIdleSeconds,
      Integer recentAuthSeconds,
      long version) {}

  @Select(
      """
      SELECT owner_type AS "ownerType",owner_tenant_id AS "ownerTenantId",password_min_chars AS "passwordMinChars",
        login_failure_threshold AS "loginFailureThreshold",login_failure_window_seconds AS "loginFailureWindowSeconds",
        login_lock_seconds AS "loginLockSeconds",login_ip_attempt_limit AS "loginIpAttemptLimit",
        login_ip_window_seconds AS "loginIpWindowSeconds",session_absolute_seconds AS "sessionAbsoluteSeconds",
        session_idle_seconds AS "sessionIdleSeconds",recent_auth_seconds AS "recentAuthSeconds",version
      FROM sys_security_policy WHERE owner_type='PLATFORM' OR (owner_type='TENANT' AND owner_tenant_id=#{tenant})
      ORDER BY owner_type
      """)
  List<Row> current(@Param("tenant") UUID tenant);

  @Select(
      """
      SELECT owner_type AS "ownerType",owner_tenant_id AS "ownerTenantId",password_min_chars AS "passwordMinChars",
        login_failure_threshold AS "loginFailureThreshold",login_failure_window_seconds AS "loginFailureWindowSeconds",
        login_lock_seconds AS "loginLockSeconds",login_ip_attempt_limit AS "loginIpAttemptLimit",
        login_ip_window_seconds AS "loginIpWindowSeconds",session_absolute_seconds AS "sessionAbsoluteSeconds",
        session_idle_seconds AS "sessionIdleSeconds",recent_auth_seconds AS "recentAuthSeconds",version
      FROM sys_security_policy WHERE owner_type='TENANT' ORDER BY owner_tenant_id
      """)
  List<Row> tenantOverrides();

  @Select("SELECT EXISTS(SELECT 1 FROM sys_password_blocklist WHERE password_digest=#{digest})")
  boolean additionallyBlocked(@Param("digest") String digest);
}
