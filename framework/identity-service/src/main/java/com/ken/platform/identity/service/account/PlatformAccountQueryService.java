package com.ken.platform.identity.service.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountQueryMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class PlatformAccountQueryService {
  public record Administrator(
      UUID id,
      String username,
      @JsonProperty("display_name") String displayName,
      String status,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      long version) {}

  public record Tenant(
      UUID id,
      String code,
      String name,
      String status,
      @JsonProperty("expires_at") Instant expiresAt,
      long version) {}

  public record Page<T>(
      int page, int size, @JsonProperty("has_more") boolean hasMore, List<T> items) {
    public Page {
      items = List.copyOf(items);
    }
  }

  private final TransactionTemplate transactions;
  private final UserManagementMapper users;
  private final AccountQueryMapper queries;
  private final PlatformManagementAuthorization authorization;
  private final ManagementAudits audits;

  public PlatformAccountQueryService(
      PlatformTransactionManager manager,
      UserManagementMapper users,
      AccountQueryMapper queries,
      PlatformManagementAuthorization authorization,
      ManagementAudits audits) {
    transactions = new TransactionTemplate(manager);
    this.users = users;
    this.queries = queries;
    this.authorization = authorization;
    this.audits = audits;
  }

  public Page<Administrator> administrators(int page, int size, String query) {
    return read(
        "platform:admin:read",
        null,
        () -> {
          int offset = offset(page, size, query);
          var rows = queries.administrators(query.strip(), offset, size + 1);
          return new Page<>(
              page,
              size,
              rows.size() > size,
              rows.stream().limit(size).map(PlatformAccountQueryService::administrator).toList());
        });
  }

  public Administrator administrator(UUID id) {
    return read(
        "platform:admin:read",
        id,
        () -> {
          var row = queries.administrator(id);
          if (row == null) throw missing();
          return administrator(row);
        });
  }

  public Page<Tenant> tenants(int page, int size, String query) {
    return read(
        "platform:tenant:read",
        null,
        () -> {
          int offset = offset(page, size, query);
          var rows = queries.tenants(query.strip(), offset, size + 1);
          return new Page<>(
              page,
              size,
              rows.size() > size,
              rows.stream().limit(size).map(PlatformAccountQueryService::tenant).toList());
        });
  }

  public Tenant tenant(UUID id) {
    return read(
        "platform:tenant:read",
        id,
        () -> {
          var row = queries.tenant(id);
          if (row == null) throw missing();
          return tenant(row);
        });
  }

  private <T> T read(String permission, UUID target, Supplier<T> action) {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        target,
        () -> {
          UUID actor = authorization.actorFor(permission);
          return transactions.execute(
              status -> {
                users.lockCatalog();
                authorization.requireActive(actor);
                return action.get();
              });
        });
  }

  private static Administrator administrator(AccountQueryMapper.Administrator row) {
    return new Administrator(
        row.id(),
        row.username(),
        row.displayName(),
        row.status(),
        row.mustChangePassword(),
        row.version());
  }

  private static Tenant tenant(AccountQueryMapper.Tenant row) {
    return new Tenant(
        row.id(), row.code(), row.name(), row.status(), row.expiresAt(), row.version());
  }

  private static int offset(int page, int size, String query) {
    if (page < 0 || page > 10000 || size < 1 || size > 100 || query == null || query.length() > 100)
      throw new PlatformException("INVALID_ARGUMENT", "分页或搜索参数不合法", 400);
    return page * size;
  }

  private static PlatformException missing() {
    return new PlatformException("NOT_FOUND", "平台账号或租户不存在", 404);
  }
}
