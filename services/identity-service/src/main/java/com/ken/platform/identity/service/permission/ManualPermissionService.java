package com.ken.platform.identity.service.permission;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.dal.mapper.permission.PermissionManagementRepository;
import com.ken.platform.identity.dal.mapper.role.RoleManagementMapper;
import com.ken.platform.identity.dal.support.IdentityTenantScope;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class ManualPermissionService {
  public record View(
      String code,
      String name,
      @JsonProperty("owner_service") String ownerService,
      @JsonProperty("principal_type") String principalType,
      @JsonProperty("group_code") String groupCode,
      @JsonProperty("group_name") String groupName,
      @JsonProperty("resource_code") String resourceCode,
      @JsonProperty("resource_name") String resourceName,
      @JsonProperty("action_code") String actionCode,
      String description,
      int sort,
      @JsonProperty("supported_data_scopes") Set<DataScope> supportedDataScopes,
      @JsonProperty("oauth_scope") String oauthScope,
      String status,
      String source,
      @JsonProperty("can_edit") boolean canEdit) {}

  public record Page<T>(
      int page,
      int size,
      @JsonProperty("has_more") boolean hasMore,
      @JsonProperty("catalog_version") long catalogVersion,
      List<T> items) {}

  public record Details(
      View permission,
      @JsonProperty("catalog_version") long catalogVersion,
      @JsonProperty("role_count") long roleCount,
      @JsonProperty("user_count") long userCount) {}

  public record Saved(View permission, @JsonProperty("catalog_version") long catalogVersion) {}

  private record Access(UUID actor, long version) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final PermissionCatalogMapper catalog;
  private final PermissionManagementRepository repository;
  private final AccountManagementMapper requests;
  private final RoleManagementMapper auditRows;
  private final PlatformManagementAuthorization authorization;
  private final IdentityTenantScope tenantScope;
  private final ManagementAudits audits;
  private final TransactionTemplate transactions;

  public ManualPermissionService(
      PermissionCatalogMapper catalog,
      PermissionManagementRepository repository,
      AccountManagementMapper requests,
      RoleManagementMapper auditRows,
      PlatformManagementAuthorization authorization,
      IdentityTenantScope tenantScope,
      ManagementAudits audits,
      PlatformTransactionManager manager) {
    this.catalog = catalog;
    this.repository = repository;
    this.requests = requests;
    this.auditRows = auditRows;
    this.authorization = authorization;
    this.tenantScope = tenantScope;
    this.audits = audits;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(10);
  }

  public List<PermissionManagementRepository.Service> services() {
    return authorized(false, ManagementAudits.Action.API_REQUEST, a -> repository.services());
  }

  public Page<View> list(
      String owner, String group, String source, String status, String query, int page, int size) {
    int offset = offset(page, size);
    String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
    if (q.length() > 160) throw invalid("搜索内容过长");
    return authorized(
        false,
        ManagementAudits.Action.API_REQUEST,
        a -> {
          var rows =
              catalog.all().stream()
                  .filter(
                      r ->
                          matches(owner, r.ownerService())
                              && matches(group, r.groupCode())
                              && matches(source, r.source())
                              && matches(status, r.status())
                              && (q.isEmpty()
                                  || (r.code() + " " + r.name())
                                      .toLowerCase(java.util.Locale.ROOT)
                                      .contains(q)))
                  .skip(offset)
                  .limit(size + 1L)
                  .map(ManualPermissionService::view)
                  .toList();
          return new Page<>(
              page, size, rows.size() > size, a.version(), rows.stream().limit(size).toList());
        });
  }

  public Details get(String code) {
    return authorized(
        false,
        ManagementAudits.Action.API_REQUEST,
        a -> {
          var row = required(code);
          long roleCount = 0, userCount = 0;
          authorization.actorFor("platform:identity:read");
          for (var tenant : repository.tenants()) {
            roleCount +=
                tenantScope.within(
                    tenant.id(), () -> (long) repository.references(tenant, code).size());
            userCount +=
                tenantScope.within(tenant.id(), () -> repository.affectedUsers(tenant.id(), code));
          }
          return new Details(view(row), a.version(), roleCount, userCount);
        });
  }

  public Page<PermissionManagementRepository.Role> references(
      String code, UUID tenant, int page, int size) {
    int offset = offset(page, size);
    return authorized(
        false,
        ManagementAudits.Action.API_REQUEST,
        a -> {
          authorization.actorFor("platform:identity:read");
          required(code);
          var tenants = repository.tenants();
          if (tenant != null && tenants.stream().noneMatch(t -> t.id().equals(tenant)))
            throw missing();
          var rows = new ArrayList<PermissionManagementRepository.Role>();
          for (var t : tenants)
            if (tenant == null || t.id().equals(tenant))
              rows.addAll(tenantScope.within(t.id(), () -> repository.references(t, code)));
          var result = rows.stream().skip(offset).limit(size + 1L).toList();
          return new Page<>(
              page, size, result.size() > size, a.version(), result.stream().limit(size).toList());
        });
  }

  public Saved create(
      String owner, PermissionCatalogService.Entry definition, Long catalogVersion) {
    long expected = version(catalogVersion);
    if (owner == null
        || owner.equals("identity-service")
        || !owner.matches("[a-z][a-z0-9-]{0,127}")) throw invalid("请选择已登记的业务服务");
    var row = PermissionCatalogService.normalize(owner, definition, "MANUAL");
    if (!row.status().equals("ACTIVE")) throw invalid("新权限状态必须为ACTIVE");
    return authorized(
        true,
        ManagementAudits.Action.PERMISSION_CREATE,
        a -> {
          String digest = digest(row);
          var previous =
              requests.prior(
                  "PLATFORM",
                  a.actor(),
                  null,
                  "platform.permission.created",
                  CallContexts.current().requestId());
          if (previous != null) {
            if (!previous.digest().equals(digest))
              throw conflict("REQUEST_ID_CONFLICT", "请求标识已用于不同权限定义");
            return new Saved(
                view(required(JSON.readTree(previous.result()).get("code").asString())),
                a.version());
          }
          expected(a, expected);
          if (repository.services().stream().noneMatch(s -> s.service().equals(owner)))
            throw invalid("业务服务尚未登记有效audience");
          if (catalog.all().stream().anyMatch(r -> r.code().equals(row.code())))
            throw conflict("PERMISSION_CODE_CONFLICT", "权限编码已存在");
          coherent(row);
          catalog.save(row);
          catalog.advance(a.version());
          requests.remember(
              "PLATFORM",
              a.actor(),
              null,
              "platform.permission.created",
              CallContexts.current().requestId(),
              digest,
              JSON.writeValueAsString(Map.of("code", row.code())));
          audit(a, "platform.permission.created", row.code(), Map.of("after", view(row)));
          return new Saved(view(row), a.version() + 1);
        });
  }

  public Saved update(
      String code,
      Long catalogVersion,
      String name,
      String groupCode,
      String groupName,
      String resourceName,
      String description,
      int sort,
      String status) {
    long expected = version(catalogVersion);
    if (!Set.of("ACTIVE", "DISABLED").contains(status == null ? "" : status))
      throw invalid("手工权限状态必须为ACTIVE或DISABLED");
    return authorized(
        true,
        ManagementAudits.Action.PERMISSION_UPDATE,
        a -> {
          expected(a, expected);
          var old = required(code);
          if (!old.source().equals("MANUAL"))
            throw new PlatformException("PERMISSION_SOURCE_PROTECTED", "系统和发布权限由原来源维护", 403);
          var definition =
              new PermissionCatalogService.Entry(
                  code,
                  name,
                  PermissionCatalogService.PrincipalType.TENANT,
                  groupCode,
                  groupName,
                  old.resourceCode(),
                  resourceName,
                  old.actionCode(),
                  description,
                  sort,
                  scopes(old.scopes()),
                  old.oauthScope(),
                  PermissionCatalogService.Status.valueOf(status));
          var row = PermissionCatalogService.normalize(old.ownerService(), definition, "MANUAL");
          if (row.equals(old)) return new Saved(view(old), a.version());
          coherent(row);
          catalog.save(row);
          catalog.advance(a.version());
          audit(
              a,
              "platform.permission.updated",
              code,
              Map.of("before", view(old), "after", view(row)));
          return new Saved(view(row), a.version() + 1);
        });
  }

  private <T> T authorized(
      boolean write, ManagementAudits.Action action, Function<Access, T> operation) {
    return audits.attempt(
        action,
        null,
        () -> {
          UUID actor =
              authorization.actorFor(
                  write ? "platform:permission:manage" : "platform:permission:read");
          try {
            return transactions.execute(
                status -> {
                  long version = catalog.lockVersion();
                  authorization.requireActive(actor);
                  return operation.apply(new Access(actor, version));
                });
          } catch (DuplicateKeyException duplicate) {
            throw conflict("PERMISSION_CODE_CONFLICT", "权限编码或服务资源操作已存在");
          }
        });
  }

  private PermissionCatalogMapper.Row required(String code) {
    return catalog.all().stream()
        .filter(r -> r.code().equals(code))
        .findFirst()
        .orElseThrow(ManualPermissionService::missing);
  }

  private void coherent(PermissionCatalogMapper.Row row) {
    var owned =
        new ArrayList<>(
            catalog.all().stream()
                .filter(
                    r ->
                        r.ownerService().equals(row.ownerService()) && !r.code().equals(row.code()))
                .toList());
    owned.add(row);
    PermissionCatalogService.coherent(owned);
  }

  private void audit(Access a, String action, String code, Object details) {
    auditRows.audit(
        a.actor(),
        "PLATFORM",
        null,
        action,
        code,
        CallContexts.current().requestId(),
        JSON.writeValueAsString(details));
  }

  private static View view(PermissionCatalogMapper.Row r) {
    return new View(
        r.code(),
        r.name(),
        r.ownerService(),
        r.principalType(),
        r.groupCode(),
        r.groupName(),
        r.resourceCode(),
        r.resourceName(),
        r.actionCode(),
        r.description(),
        r.sort(),
        scopes(r.scopes()),
        r.oauthScope(),
        r.status(),
        r.source(),
        r.source().equals("MANUAL"));
  }

  private static Set<DataScope> scopes(String value) {
    return value == null || value.isEmpty()
        ? Set.of()
        : Arrays.stream(value.split(",")).map(DataScope::valueOf).collect(Collectors.toSet());
  }

  private static boolean matches(String filter, String value) {
    return filter == null || filter.isBlank() || filter.equals(value);
  }

  private static int offset(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("分页参数不合法");
    return page * size;
  }

  private static long version(Long value) {
    if (value == null || value < 0) throw invalid("缺少目录版本");
    return value;
  }

  private static void expected(Access a, long value) {
    if (a.version() != value) throw conflict("CATALOG_VERSION_CONFLICT", "权限目录已变化，请刷新");
  }

  private static String digest(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("INVALID_ARGUMENT", message, 400);
  }

  private static PlatformException missing() {
    return new PlatformException("NOT_FOUND", "权限或租户不存在", 404);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
