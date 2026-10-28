package com.ken.platform.identity.dal.mapper.login;

import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoginStore {
  public record Tenant(UUID id, String status, Instant expiresAt, long authVersion) {}

  public record Credential(
      UUID id, String hash, String status, boolean mustChange, long authVersion, long version) {
    @Override
    public String toString() {
      return "Credential[id=" + id + "]";
    }
  }

  public record Profile(UUID id, String username, String displayName, long version) {}

  public record Session(
      UUID id,
      LoginRealm realm,
      UUID tenant,
      UUID user,
      long authVersion,
      Long tenantAuthVersion,
      boolean restricted,
      Instant createdAt,
      Instant lastActivity,
      Instant revokedAt,
      String redisSessionId,
      boolean cleanupPending) {
    @Override
    public String toString() {
      return "LoginSession[id=" + id + "]";
    }
  }

  private final JdbcTemplate jdbc;
  private final IdentityTenantScope tenantScope;

  public LoginStore(JdbcTemplate jdbc, IdentityTenantScope tenantScope) {
    this.jdbc = jdbc;
    this.tenantScope = tenantScope;
  }

  public void lockCatalog() {
    jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR SHARE", Long.class);
  }

  public void lockSessionManagement() {
    jdbc.queryForObject(
        "SELECT version FROM sys_permission_catalog_state WHERE id=1 FOR UPDATE", Long.class);
  }

  public java.util.List<Session> ownSessions(
      Session owner, Integer limit, int offset, boolean lock) {
    String sql =
        "SELECT * FROM sys_login_session WHERE realm=? AND tenant_id IS NOT DISTINCT FROM ? AND COALESCE(user_id,platform_user_id)=? ORDER BY id";
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(owner.realm().name());
    arguments.add(owner.tenant());
    arguments.add(owner.user());
    if (limit != null) {
      sql += " LIMIT ? OFFSET ?";
      arguments.add(limit);
      arguments.add(offset);
    }
    if (lock) sql += " FOR UPDATE";
    return jdbc.query(sql, (row, index) -> readSession(row), arguments.toArray());
  }

  public java.util.List<String> sessionApplications(UUID id) {
    return jdbc.queryForList(
        "SELECT DISTINCT c.client_id FROM oauth2_authorization a JOIN oauth2_registered_client c ON c.id=a.registered_client_id WHERE a.attributes::jsonb->>'platform.issued.session'=? ORDER BY c.client_id",
        String.class,
        id.toString());
  }

  public java.util.List<Session> sessionsForTenantUser(UUID tenant, UUID user) {
    return jdbc.query(
        "SELECT * FROM sys_login_session WHERE realm='TENANT' AND tenant_id=? AND user_id=? ORDER BY id FOR UPDATE",
        (row, index) -> readSession(row),
        tenant,
        user);
  }

  public int incrementAuthVersion(Session owner) {
    return scoped(
        owner.tenant(),
        () ->
            jdbc.update(
                "UPDATE "
                    + (owner.realm() == LoginRealm.PLATFORM ? "sys_platform_user" : "sys_user")
                    + " SET auth_version=auth_version+1,version=version+1,updated_at=now() WHERE id=? AND auth_version=?",
                owner.user(),
                owner.authVersion()));
  }

  public int changeOwnPassword(Session owner, String hash) {
    return scoped(
        owner.tenant(),
        () ->
            jdbc.update(
                "UPDATE "
                    + (owner.realm() == LoginRealm.PLATFORM ? "sys_platform_user" : "sys_user")
                    + " SET password_hash=?,auth_version=auth_version+1,version=version+1,updated_at=now()"
                    + " WHERE id=? AND auth_version=? AND status='ACTIVE' AND NOT must_change_password",
                hash,
                owner.user(),
                owner.authVersion()));
  }

  public Profile ownProfile(Session owner) {
    return scoped(
        owner.tenant(),
        () ->
            jdbc
                .query(
                    "SELECT id,normalized_username,display_name,version FROM "
                        + (owner.realm() == LoginRealm.PLATFORM ? "sys_platform_user" : "sys_user")
                        + " WHERE id=?",
                    (row, index) ->
                        new Profile(
                            row.getObject("id", UUID.class),
                            row.getString("normalized_username"),
                            row.getString("display_name"),
                            row.getLong("version")),
                    owner.user())
                .stream()
                .findFirst()
                .orElse(null));
  }

  public int changeOwnDisplayName(Session owner, long version, String name) {
    return scoped(
        owner.tenant(),
        () ->
            jdbc.update(
                "UPDATE "
                    + (owner.realm() == LoginRealm.PLATFORM ? "sys_platform_user" : "sys_user")
                    + " SET display_name=?,version=version+1,updated_at=now() WHERE id=? AND version=? AND status='ACTIVE' AND NOT must_change_password",
                name,
                owner.user(),
                version));
  }

  public void auditOwnProfile(
      Session owner, String previous, String name, long version, String request) {
    jdbc.update(
        """
        INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id,details)
        VALUES(?,?,?,'identity.own-profile.updated',?,'SUCCESS',?,
          jsonb_build_object('before',jsonb_build_object('display_name',?::text),
            'after',jsonb_build_object('display_name',?::text),'version',?::bigint))
        """,
        owner.user(),
        owner.realm().name(),
        owner.tenant(),
        owner.user().toString(),
        request,
        previous,
        name,
        version);
  }

  public UUID resolveTenant(String code) {
    return jdbc.queryForList("SELECT id FROM sys_tenant WHERE code=?", UUID.class, code).stream()
        .findFirst()
        .orElse(null);
  }

  public Tenant lockTenant(UUID id) {
    return jdbc
        .query(
            "SELECT id,status,expires_at,auth_version FROM sys_tenant WHERE id=? FOR SHARE",
            (row, index) ->
                new Tenant(
                    row.getObject("id", UUID.class),
                    row.getString("status"),
                    instant(row, "expires_at"),
                    row.getLong("auth_version")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Credential credential(LoginRealm realm, UUID tenant, String username, UUID id) {
    return scoped(
        tenant,
        () -> {
          String sql =
              realm == LoginRealm.PLATFORM
                  ? "SELECT id,password_hash,status,must_change_password,auth_version,version FROM sys_platform_user WHERE "
                  : "SELECT id,password_hash,status,must_change_password,auth_version,version FROM sys_user WHERE ";
          sql += (id == null ? "normalized_username=?" : "id=?") + " FOR UPDATE";
          return jdbc
              .query(
                  sql,
                  (row, index) ->
                      new Credential(
                          row.getObject("id", UUID.class),
                          row.getString("password_hash"),
                          row.getString("status"),
                          row.getBoolean("must_change_password"),
                          row.getLong("auth_version"),
                          row.getLong("version")),
                  id == null ? username : id)
              .stream()
              .findFirst()
              .orElse(null);
        });
  }

  public void createSession(Session value) {
    jdbc.update(
        """
        INSERT INTO sys_login_session(id,realm,tenant_id,user_id,platform_user_id,auth_version,tenant_auth_version,restricted,created_at,last_activity_at)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
        value.id(),
        value.realm().name(),
        value.tenant(),
        value.realm() == LoginRealm.TENANT ? value.user() : null,
        value.realm() == LoginRealm.PLATFORM ? value.user() : null,
        value.authVersion(),
        value.tenantAuthVersion(),
        value.restricted(),
        java.sql.Timestamp.from(value.createdAt()),
        java.sql.Timestamp.from(value.lastActivity()));
  }

  public Session session(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM sys_login_session WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (row, index) -> readSession(row),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void revoke(UUID id, Instant now) {
    jdbc.update(
        "UPDATE sys_login_session SET revoked_at=COALESCE(revoked_at,?),cleanup_pending=(redis_session_id IS NOT NULL) WHERE id=?",
        java.sql.Timestamp.from(now),
        id);
  }

  public int attachRedisSession(UUID id, String redisId) {
    return jdbc.update(
        "UPDATE sys_login_session SET redis_session_id=? WHERE id=? AND revoked_at IS NULL AND (redis_session_id IS NULL OR redis_session_id=?)",
        redisId,
        id,
        redisId);
  }

  public java.util.List<UUID> pendingCleanup() {
    return jdbc.queryForList(
        "SELECT id FROM sys_login_session WHERE cleanup_pending ORDER BY revoked_at,id LIMIT 100",
        UUID.class);
  }

  public void cleanupCompleted(UUID id, String redisId) {
    jdbc.update(
        "UPDATE sys_login_session SET cleanup_pending=false WHERE id=? AND revoked_at IS NOT NULL AND redis_session_id=?",
        id,
        redisId);
  }

  public void touch(UUID id, Instant now) {
    jdbc.update(
        "UPDATE sys_login_session SET last_activity_at=GREATEST(last_activity_at,?) WHERE id=?",
        java.sql.Timestamp.from(now),
        id);
  }

  public int firstPassword(LoginRealm realm, UUID tenant, Credential old, String hash) {
    return scoped(
        tenant,
        () -> {
          String table = realm == LoginRealm.PLATFORM ? "sys_platform_user" : "sys_user";
          return jdbc.update(
              "UPDATE "
                  + table
                  + " SET password_hash=?,must_change_password=false,status='ACTIVE',auth_version=auth_version+1,version=version+1,updated_at=now() WHERE id=? AND auth_version=? AND must_change_password",
              hash,
              old.id(),
              old.authVersion());
        });
  }

  public void activation(UUID tenant, UUID user, long version, Instant now) {
    jdbc.update(
        """
        INSERT INTO identity_technical.outbox(event_id,event_type,schema_version,tenant_id,producer,aggregate_id,aggregate_version,occurred_at,data)
        VALUES(?,'identity.user.activated.v1',1,?,'identity-service',?,?,?,jsonb_build_object('user_id',?::text,'user_version',?::bigint))
        """,
        UUID.randomUUID(),
        tenant,
        user,
        version,
        java.sql.Timestamp.from(now),
        user,
        version);
  }

  public void audit(Session session, String action, String request) {
    jdbc.update(
        """
        INSERT INTO sys_auth_audit(actor_id,actor_type,tenant_id,action,target_id,result,request_id)
        VALUES(?,?,?,?,?,'SUCCESS',?)
        """,
        session.user(),
        session.realm().name(),
        session.tenant(),
        action,
        session.id().toString(),
        request);
  }

  private <T> T scoped(UUID tenant, Supplier<T> operation) {
    return tenantScope.within(tenant, operation);
  }

  private static Instant instant(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    var value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Session readSession(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Session(
        row.getObject("id", UUID.class),
        LoginRealm.valueOf(row.getString("realm")),
        row.getObject("tenant_id", UUID.class),
        row.getObject(
            row.getString("realm").equals("TENANT") ? "user_id" : "platform_user_id", UUID.class),
        row.getLong("auth_version"),
        row.getObject("tenant_auth_version", Long.class),
        row.getBoolean("restricted"),
        instant(row, "created_at"),
        instant(row, "last_activity_at"),
        instant(row, "revoked_at"),
        row.getString("redis_session_id"),
        row.getBoolean("cleanup_pending"));
  }
}
