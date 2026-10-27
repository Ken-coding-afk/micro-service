package com.ken.platform.identity.dal.mapper.oauth;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 官方 JDBC 的受控双表存取；一条 SELECT 读取协议和扩展，避免跨语句混合版本。 */
@Repository
public class ClientStore {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClientStore.class);

  public record Snapshot(RegisteredClient client, ClientPolicyDO policy) {
    @Override
    public String toString() {
      return "ClientSnapshot[id=" + client.getId() + ", version=" + policy.version() + "]";
    }
  }

  private static final String SELECT =
      """
      SELECT c.*,p.registered_client_id AS policy_id,p.principal_type AS policy_type,
        p.owner_tenant_id,p.allowed_tenant_ids,p.audiences,p.status AS policy_status,p.version AS policy_version
      FROM oauth2_registered_client c LEFT JOIN sys_client_policy p ON p.registered_client_id=c.id
      """;

  private static final class OfficialClients extends JdbcRegisteredClientRepository {
    OfficialClients(JdbcTemplate jdbc) {
      super(jdbc);
    }

    RowMapper<RegisteredClient> rowMapper() {
      return getRegisteredClientRowMapper();
    }
  }

  private final JdbcTemplate jdbc;
  private final OfficialClients official;

  public ClientStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.official = new OfficialClients(jdbc);
  }

  public Snapshot byId(String id) {
    return read(SELECT + " WHERE c.id=?", id);
  }

  public Snapshot byClientId(String clientId) {
    return read(SELECT + " WHERE c.client_id=?", clientId);
  }

  public java.util.List<String> pageIds(String query, int offset, int limit) {
    return jdbc.queryForList(
        "SELECT id FROM oauth2_registered_client WHERE ?='' OR position(lower(?) in lower(client_id || ' ' || client_name))>0 ORDER BY client_id,id LIMIT ? OFFSET ?",
        String.class,
        query,
        query,
        limit,
        offset);
  }

  public boolean clientIdExists(String clientId) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM oauth2_registered_client WHERE client_id=?)",
        Boolean.class,
        clientId);
  }

  public record BootstrapClient(String id, String digest) {}

  public BootstrapClient bootstrapClient(String purpose) {
    return jdbc
        .query(
            "SELECT registered_client_id,definition_digest FROM sys_identity_bootstrap_client WHERE purpose=?",
            (row, index) -> new BootstrapClient(row.getString(1), row.getString(2)),
            purpose)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void rememberBootstrap(String purpose, String id, String digest) {
    jdbc.update(
        "INSERT INTO sys_identity_bootstrap_client(purpose,registered_client_id,definition_digest) VALUES(?,?,?)",
        purpose,
        id,
        digest);
  }

  private Snapshot read(String sql, String id) {
    return jdbc
        .query(
            sql,
            (row, index) -> {
              if (row.getString("policy_id") == null) {
                throw configuration();
              }
              try {
                return new Snapshot(
                    official.rowMapper().mapRow(row, index),
                    new ClientPolicyDO(
                        PrincipalType.valueOf(
                            row.getString("policy_type").toUpperCase(java.util.Locale.ROOT)),
                        row.getObject("owner_tenant_id", UUID.class),
                        uuidSet(row, "allowed_tenant_ids"),
                        stringSet(row, "audiences"),
                        Status.valueOf(row.getString("policy_status")),
                        row.getLong("policy_version")));
              } catch (IllegalArgumentException malformed) {
                throw configuration();
              }
            },
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public long lockPolicy(String id) {
    var rows =
        jdbc.queryForList(
            "SELECT version FROM sys_client_policy WHERE registered_client_id=? FOR UPDATE",
            Long.class,
            id);
    if (rows.isEmpty()) {
      throw new PlatformException("NOT_FOUND", "客户端不存在或扩展配置缺失", 404);
    }
    return rows.getFirst();
  }

  public Snapshot lockSnapshot(String id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("协议策略锁必须位于事务内");
    }
    // 与统一客户端写入口的 FOR UPDATE 互斥，持有到最终授权写入提交。
    jdbc.queryForList(
        "SELECT registered_client_id FROM sys_client_policy WHERE registered_client_id=? FOR SHARE",
        String.class,
        id);
    return byId(id);
  }

  public Set<UUID> activeTenants(Set<UUID> tenants) {
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT id FROM sys_tenant WHERE id=ANY(?) AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>now())",
            UUID.class,
            new SqlArrayValue("uuid", tenants.toArray())));
  }

  public int tenantCount(Set<UUID> tenants) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM sys_tenant WHERE id=ANY(?)",
        Integer.class,
        new SqlArrayValue("uuid", tenants.toArray()));
  }

  public record Audience(String audience, String service, String status) {}

  public java.util.List<Audience> findAudienceBindings(String service, String audience) {
    return jdbc.query(
        "SELECT audience,owner_service,status FROM sys_service_audience WHERE owner_service=? OR audience=?",
        (row, index) -> new Audience(row.getString(1), row.getString(2), row.getString(3)),
        service,
        audience);
  }

  public void registerAudience(String service, String audience) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("登记必须在事务中");
    jdbc.update(
        "INSERT INTO sys_service_audience(owner_service,audience) VALUES(?,?)", service, audience);
  }

  public int audienceCount(Set<String> audiences) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM sys_service_audience WHERE audience=ANY(?) AND status='ACTIVE'",
        Integer.class,
        new SqlArrayValue("text", audiences.toArray()));
  }

  public Set<String> businessScopes(PrincipalType type, Set<String> audiences) {
    return Set.copyOf(
        jdbc.queryForList(
            """
        SELECT DISTINCT p.oauth_scope FROM sys_permission p JOIN sys_service_audience a ON a.owner_service=p.owner_service
        WHERE p.status='ACTIVE' AND p.principal_type=? AND a.status='ACTIVE' AND a.audience=ANY(?)
        """,
            String.class,
            type == PrincipalType.PLATFORM ? "PLATFORM" : "TENANT",
            new SqlArrayValue("text", audiences.toArray())));
  }

  public boolean audienceExists(String audience) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM sys_service_audience WHERE audience=? AND status='ACTIVE')",
        Boolean.class,
        audience);
  }

  public void savePair(RegisteredClient client, ClientPolicyDO policy, Long expectedVersion) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("客户端协议与策略必须同事务写入");
    }
    official.save(client);
    if (expectedVersion == null) {
      jdbc.update(
          """
          INSERT INTO sys_client_policy(registered_client_id,principal_type,owner_tenant_id,allowed_tenant_ids,audiences,status,version)
          VALUES(?,?,?,?,?,?,?)
          """,
          client.getId(),
          policy.principalType().name().toLowerCase(java.util.Locale.ROOT),
          policy.ownerTenantId(),
          new SqlArrayValue("uuid", policy.allowedTenantIds().toArray()),
          new SqlArrayValue("text", policy.audiences().toArray()),
          policy.status().name(),
          policy.version());
    } else {
      int changed =
          jdbc.update(
              """
          UPDATE sys_client_policy SET allowed_tenant_ids=?,audiences=?,status=?,version=?,updated_at=now()
          WHERE registered_client_id=? AND version=?
          """,
              new SqlArrayValue("uuid", policy.allowedTenantIds().toArray()),
              new SqlArrayValue("text", policy.audiences().toArray()),
              policy.status().name(),
              policy.version(),
              client.getId(),
              expectedVersion);
      if (changed != 1) {
        throw new PlatformException("CLIENT_POLICY_VERSION_CONFLICT", "客户端配置已变化", 409);
      }
    }
  }

  private static Set<UUID> uuidSet(ResultSet row, String name) throws SQLException {
    Array array = row.getArray(name);
    try {
      return Arrays.stream((Object[]) array.getArray())
          .map(UUID.class::cast)
          .collect(Collectors.toUnmodifiableSet());
    } finally {
      array.free();
    }
  }

  private static Set<String> stringSet(ResultSet row, String name) throws SQLException {
    Array array = row.getArray(name);
    try {
      return Arrays.stream((Object[]) array.getArray())
          .map(String.class::cast)
          .collect(Collectors.toUnmodifiableSet());
    } finally {
      array.free();
    }
  }

  private static PlatformException configuration() {
    LOG.error("client.configuration.invalid");
    return new PlatformException("CLIENT_CONFIGURATION_ERROR", "客户端协议与扩展配置不完整", 503);
  }
}
