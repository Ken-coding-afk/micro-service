package com.ken.platform.identity.dal.mapper.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityPolicyStore {
  public static final List<String> FIELDS =
      List.of(
          "password_min_chars",
          "login_failure_threshold",
          "login_failure_window_seconds",
          "login_lock_seconds",
          "login_ip_attempt_limit",
          "login_ip_window_seconds",
          "session_absolute_seconds",
          "session_idle_seconds",
          "recent_auth_seconds");
  private final JdbcTemplate jdbc;

  public SecurityPolicyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(UUID tenant) {
    jdbc.queryForList(
        "SELECT id FROM sys_security_policy WHERE owner_type='PLATFORM' OR owner_tenant_id=? ORDER BY owner_type,owner_tenant_id FOR UPDATE",
        UUID.class,
        tenant);
  }

  public boolean capability(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM sys_tenant_capability WHERE tenant_id=? AND capability='SECURITY_POLICY_MANAGEMENT' AND enabled)",
        Boolean.class,
        tenant);
  }

  public void insert(UUID tenant, Map<String, Integer> values) {
    var arguments = arguments(values);
    arguments.add(tenant);
    jdbc.update(
        "INSERT INTO sys_security_policy("
            + String.join(",", FIELDS)
            + ",owner_type,owner_tenant_id) VALUES(?,?,?,?,?,?,?,?,?,'TENANT',?)",
        arguments.toArray());
  }

  public int update(UUID tenant, long version, Map<String, Integer> values) {
    var arguments = arguments(values);
    arguments.add(tenant == null ? "PLATFORM" : "TENANT");
    arguments.add(tenant);
    arguments.add(version);
    String assignments =
        FIELDS.stream()
            .map(field -> field + "=?")
            .collect(java.util.stream.Collectors.joining(","));
    return jdbc.update(
        "UPDATE sys_security_policy SET "
            + assignments
            + ",version=version+1,updated_at=now() WHERE owner_type=? AND owner_tenant_id IS NOT DISTINCT FROM ? AND version=?",
        arguments.toArray());
  }

  private static ArrayList<Object> arguments(Map<String, Integer> values) {
    var result = new ArrayList<Object>();
    for (String field : FIELDS) result.add(values.get(field));
    return result;
  }
}
