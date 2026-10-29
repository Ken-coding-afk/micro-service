package com.ken.platform.identity.dal.mapper.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 独立安全事件仅保存受验证身份元数据与稳定错误码，不接收请求正文或异常消息。 */
@Repository
public class SecurityAuditRepository {
  private final JdbcTemplate jdbc;

  public SecurityAuditRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID existingTenant(UUID requested) {
    return jdbc
        .query(
            "SELECT id FROM sys_tenant WHERE id=?",
            (row, index) -> row.getObject("id", UUID.class),
            requested)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void failure(
      UUID actor,
      String actorType,
      UUID tenant,
      String action,
      UUID target,
      String requestId,
      String details) {
    jdbc.update(
        """
        INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
        VALUES(?,?,?,?,?,'FAILURE',?,CAST(? AS jsonb))
        """,
        actor,
        actorType,
        tenant,
        action,
        target == null ? null : target.toString(),
        requestId,
        details);
  }

  public void protocolEvent(
      String action, String result, String client, String code, String request) {
    if (!java.util.Set.of(
                "identity.oauth.client_authentication",
                "identity.oauth.token",
                "identity.oauth.introspection",
                "identity.oauth.revocation",
                "identity.oauth.protocol")
            .contains(action)
        || !java.util.Set.of("SUCCESS", "FAILURE").contains(result)
        || code != null && !code.matches("[a-z_]{1,64}")) {
      throw new IllegalArgumentException("安全事件动作/结果不合法");
    }
    var details = new java.util.HashMap<String, String>();
    if (client != null) details.put("client_id", client);
    if (code != null) details.put("error_code", code);
    jdbc.update(
        "INSERT INTO sys_auth_audit(actor_type,action,result,request_id,details) VALUES(?,?,?,?,CAST(? AS jsonb))",
        client == null ? "ANONYMOUS" : "SERVICE",
        action,
        result,
        request,
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(details));
  }

  public void userProtocolEvent(
      String action,
      String result,
      UUID actor,
      String actorType,
      UUID tenant,
      String client,
      String code,
      String request) {
    if (!java.util.Set.of("identity.oauth.authorization", "identity.oauth.token").contains(action)
        || !java.util.Set.of("SUCCESS", "FAILURE").contains(result)
        || !java.util.Set.of("ANONYMOUS", "PLATFORM", "TENANT").contains(actorType)
        || code != null && !code.matches("[a-z_]{1,64}"))
      throw new IllegalArgumentException("用户协议事件字段不合法");
    var details = new java.util.HashMap<String, String>();
    if (client != null) details.put("client_id", client);
    if (code != null) details.put("error_code", code);
    jdbc.update(
        "INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,result,request_id,details) VALUES(?,?,?,?,?,?,CAST(? AS jsonb))",
        actor,
        actorType,
        tenant,
        action,
        result,
        request,
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(details));
  }
}
