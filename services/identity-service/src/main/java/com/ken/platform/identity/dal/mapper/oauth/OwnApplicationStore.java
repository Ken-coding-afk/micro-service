package com.ken.platform.identity.dal.mapper.oauth;

import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OwnApplicationStore {
  public record Application(String id, String clientId, String name, String status) {}

  private final JdbcTemplate jdbc;

  public OwnApplicationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Application> consents(Identity owner, int offset, int limit) {
    return jdbc.query(
        """
        SELECT c.id,c.client_id,c.client_name,p.status FROM oauth2_authorization_consent a
        JOIN oauth2_registered_client c ON c.id=a.registered_client_id
        JOIN sys_client_policy p ON p.registered_client_id=c.id
        WHERE a.principal_name=? AND p.principal_type=? ORDER BY c.client_id LIMIT ? OFFSET ?
        """,
        (row, index) ->
            new Application(
                row.getString("id"),
                row.getString("client_id"),
                row.getString("client_name"),
                row.getString("status")),
        owner.user().toString(),
        owner.tenant() == null ? "platform" : "user",
        limit,
        offset);
  }

  public void audit(Identity owner, String client, int revoked, boolean removed, String request) {
    jdbc.update(
        """
        INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
        VALUES(?,?,?,'identity.consent.revoked',?,'SUCCESS',?,jsonb_build_object('authorizations_revoked',?::integer,'consent_removed',?::boolean))
        """,
        owner.user(),
        owner.realm().name(),
        owner.tenant(),
        client,
        request,
        revoked,
        removed);
  }
}
