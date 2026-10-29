package com.ken.platform.identity.dal.mapper.permission;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PermissionManagementRepository {
  public record Service(String service, String audience) {}

  public record Tenant(UUID id, String name) {}

  public record Role(
      UUID id,
      String name,
      String status,
      @JsonProperty("tenant_id") UUID tenantId,
      @JsonProperty("tenant_name") String tenantName,
      @JsonProperty("data_scope") String dataScope) {}

  private final JdbcTemplate jdbc;

  public PermissionManagementRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Service> services() {
    return jdbc.query(
        "SELECT owner_service,audience FROM sys_service_audience WHERE status='ACTIVE' AND owner_service<>'identity-service' ORDER BY owner_service",
        (rs, n) -> new Service(rs.getString(1), rs.getString(2)));
  }

  public List<Tenant> tenants() {
    return jdbc.query(
        "SELECT id,name FROM sys_tenant ORDER BY id",
        (rs, n) -> new Tenant(rs.getObject(1, UUID.class), rs.getString(2)));
  }

  public List<Role> references(Tenant tenant, String code) {
    return jdbc.query(
        "SELECT r.id,r.name,r.status,p.data_scope FROM sys_role r JOIN sys_role_permission p ON p.tenant_id=r.tenant_id AND p.role_id=r.id WHERE r.tenant_id=? AND p.permission_code=? ORDER BY r.id",
        (rs, n) ->
            new Role(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                tenant.id(),
                tenant.name(),
                rs.getString(4)),
        tenant.id(),
        code);
  }

  public long affectedUsers(UUID tenant, String code) {
    return jdbc.queryForObject(
        """
        SELECT count(DISTINCT source.user_id) FROM sys_user_role_source source
        JOIN sys_role_permission p ON p.tenant_id=source.tenant_id AND p.role_id=source.role_id
        JOIN sys_role r ON r.tenant_id=source.tenant_id AND r.id=source.role_id AND r.status='ACTIVE'
        JOIN sys_user u ON u.tenant_id=source.tenant_id AND u.id=source.user_id AND u.status='ACTIVE'
        WHERE source.tenant_id=? AND p.permission_code=?
        """,
        Long.class,
        tenant,
        code);
  }
}
