package com.ken.platform.identity.dal.mapper.login;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetStore {
  public record Ticket(
      UUID tenant, UUID user, long authVersion, Instant expires, Instant consumed) {}

  private final JdbcTemplate jdbc;

  public PasswordResetStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Ticket find(String hash) {
    return jdbc
        .query(
            "SELECT * FROM identity_technical.password_reset WHERE token_hash=?",
            (r, n) ->
                new Ticket(
                    r.getObject("tenant_id", UUID.class),
                    r.getObject("user_id", UUID.class),
                    r.getLong("auth_version"),
                    r.getTimestamp("expires_at").toInstant(),
                    r.getTimestamp("consumed_at") == null
                        ? null
                        : r.getTimestamp("consumed_at").toInstant()),
            hash)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void issue(String hash, UUID tenant, UUID user, long version, Instant expires) {
    jdbc.update(
        "UPDATE identity_technical.password_reset SET consumed_at=now() WHERE tenant_id=? AND user_id=? AND consumed_at IS NULL",
        tenant,
        user);
    jdbc.update(
        "INSERT INTO identity_technical.password_reset(token_hash,tenant_id,user_id,auth_version,expires_at) VALUES(?,?,?,?,?)",
        hash,
        tenant,
        user,
        version,
        java.sql.Timestamp.from(expires));
  }

  public boolean consume(String hash, Instant now) {
    return jdbc.update(
            "UPDATE identity_technical.password_reset SET consumed_at=? WHERE token_hash=? AND consumed_at IS NULL AND expires_at>?",
            java.sql.Timestamp.from(now),
            hash,
            java.sql.Timestamp.from(now))
        == 1;
  }

  public String password(UUID tenant, UUID user) {
    return jdbc.queryForObject(
        "SELECT password_hash FROM sys_user WHERE tenant_id=? AND id=?",
        String.class,
        tenant,
        user);
  }

  public int change(UUID tenant, UUID user, long version, String hash) {
    return jdbc.update(
        "UPDATE sys_user SET password_hash=?,status='ACTIVE',must_change_password=false,version=version+1,auth_version=auth_version+1,updated_at=now() WHERE tenant_id=? AND id=? AND auth_version=? AND status<>'DISABLED'",
        hash,
        tenant,
        user,
        version);
  }
}
