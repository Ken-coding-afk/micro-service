package com.ken.platform.identity.service.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.audit.AuditQueryMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class AuditQueryService {
  public record Entry(
      UUID id,
      @JsonProperty("actor_id") UUID actorId,
      @JsonProperty("actor_type") String actorType,
      @JsonProperty("tenant_id") UUID tenantId,
      String action,
      @JsonProperty("target_id") String targetId,
      String result,
      @JsonProperty("request_id") String requestId,
      Map<String, Object> details,
      @JsonProperty("details_truncated") boolean detailsTruncated,
      @JsonProperty("created_at") Instant createdAt) {}

  public record Page(
      int page, int size, @JsonProperty("has_more") boolean hasMore, List<Entry> items) {
    public Page {
      items = List.copyOf(items);
    }
  }

  private final TransactionTemplate control;
  private final TenantTransactions tenants;
  private final UserManagementMapper users;
  private final AuditQueryMapper queries;
  private final PlatformManagementAuthorization platform;
  private final TenantManagementAuthorization tenant;
  private final AuditDetailSanitizer sanitizer;
  private final ManagementAudits audits;
  private final Clock clock;

  public AuditQueryService(
      PlatformTransactionManager manager,
      TenantTransactions tenants,
      UserManagementMapper users,
      AuditQueryMapper queries,
      PlatformManagementAuthorization platform,
      TenantManagementAuthorization tenant,
      AuditDetailSanitizer sanitizer,
      ManagementAudits audits,
      Clock clock) {
    control = new TransactionTemplate(manager);
    control.setTimeout(10);
    this.tenants = tenants;
    this.users = users;
    this.queries = queries;
    this.platform = platform;
    this.tenant = tenant;
    this.sanitizer = sanitizer;
    this.audits = audits;
    this.clock = clock;
  }

  public Page query(
      boolean platformScope,
      int page,
      int size,
      Instant from,
      Instant to,
      UUID actor,
      String action,
      String target,
      String result) {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        null,
        () -> {
          if (page < 0
              || page > 10000
              || size < 1
              || size > 100
              || from != null && to != null && !from.isBefore(to)
              || action != null && !action.matches("[a-z][a-z0-9._:-]{0,159}")
              || target != null && !target.matches("[A-Za-z0-9._:/-]{1,160}")
              || result != null && !Set.of("SUCCESS", "FAILURE").contains(result))
            throw new PlatformException("INVALID_ARGUMENT", "审计分页、时间或筛选字段不合法", 400);
          var filter =
              new AuditQueryMapper.Filter(
                  from, to, actor, action, target, result, page * size, size + 1);
          List<AuditQueryMapper.Row> rows;
          if (platformScope) {
            UUID user = platform.actorFor("platform:audit:read");
            rows =
                control.execute(
                    status -> {
                      users.lockCatalog();
                      platform.requireActive(user);
                      return queries.platform(filter);
                    });
          } else {
            UUID user = tenant.actorFor("identity:audit:read");
            rows =
                tenant.execute(
                    tenants,
                    () -> {
                      UUID owner = tenant.tenantId();
                      tenant.lockCatalog();
                      var directory = tenant.lockTenant(owner);
                      if (directory == null
                          || !directory.status().equals("ACTIVE")
                          || directory.expiresAt() != null
                              && !clock.instant().isBefore(directory.expiresAt()))
                        throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                      tenant.lockMemberRoles(owner, tenant.userIds(user, null));
                      var acting = tenant.lockUsers(owner, tenant.userIds(user, null));
                      tenant.requireProtectedAdministrator(
                          owner, user, "identity:audit:read", acting);
                      return queries.tenant(owner, filter);
                    });
          }
          var entries =
              rows.stream()
                  .limit(size)
                  .map(
                      row -> {
                        var details = sanitizer.sanitize(row.details());
                        return new Entry(
                            row.id(),
                            row.actorId(),
                            row.actorType(),
                            row.tenantId(),
                            row.action(),
                            row.targetId(),
                            row.result(),
                            row.requestId(),
                            details.details(),
                            details.truncated(),
                            row.createdAt());
                      })
                  .toList();
          return new Page(page, size, rows.size() > size, entries);
        });
  }
}
