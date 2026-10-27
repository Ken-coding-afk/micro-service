package com.ken.platform.identity.service.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository;
import com.ken.platform.identity.dal.mapper.management.ManagementQueryMapper;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.role.RolePermissionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 查询与提交共享固定管理员身份和租户边界；返回的是当前快照，不能代替写入时再次核验。 */
@Service
public final class TenantManagementQueryService {
  public record Page<T>(
      int page, int size, @JsonProperty("has_more") boolean hasMore, List<T> items) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record User(
      UUID id,
      String username,
      @JsonProperty("display_name") String displayName,
      String status,
      @JsonProperty("must_change_password") boolean mustChangePassword,
      long version) {}

  public record Role(
      UUID id,
      String code,
      String name,
      String status,
      @JsonProperty("protected") boolean protectedRole,
      long version,
      @JsonProperty("authorization_version") long authorizationVersion,
      @JsonProperty("can_assign") boolean canAssign,
      @JsonProperty("can_edit") boolean canEdit,
      @JsonProperty("can_unassign") boolean canUnassign,
      @JsonProperty("can_manage") boolean canManage,
      List<String> restrictions,
      String description) {
    public Role {
      restrictions = List.copyOf(restrictions);
    }
  }

  public record RolePage(
      int page,
      int size,
      @JsonProperty("has_more") boolean hasMore,
      List<Role> items,
      @JsonProperty("can_create") boolean canCreate) {
    public RolePage {
      items = List.copyOf(items);
    }
  }

  public record UserRoles(@JsonProperty("user_id") UUID userId, long version, List<Role> roles) {
    public UserRoles {
      roles = List.copyOf(roles);
    }
  }

  public record RolePermissions(
      Role role,
      @JsonProperty("catalog_version") long catalogVersion,
      List<RolePermissionService.Grant> grants) {
    public RolePermissions {
      grants = List.copyOf(grants);
    }
  }

  public record Permission(
      String code,
      String name,
      @JsonProperty("owner_service") String ownerService,
      @JsonProperty("group_code") String groupCode,
      @JsonProperty("group_name") String groupName,
      @JsonProperty("resource_code") String resourceCode,
      @JsonProperty("resource_name") String resourceName,
      @JsonProperty("action_code") String actionCode,
      String description,
      int sort,
      String status,
      @JsonProperty("oauth_scope") String oauthScope,
      @JsonProperty("supported_data_scopes") Set<DataScope> supportedDataScopes,
      @JsonProperty("assignable_data_scopes") Set<DataScope> assignableDataScopes,
      @JsonProperty("can_assign") boolean canAssign) {
    public Permission {
      supportedDataScopes = Set.copyOf(supportedDataScopes);
      assignableDataScopes = Set.copyOf(assignableDataScopes);
    }
  }

  public record Catalog(
      @JsonProperty("catalog_version") long catalogVersion, List<Permission> items) {
    public Catalog {
      items = List.copyOf(items);
    }
  }

  public record Source(
      @JsonProperty("role_id") UUID roleId,
      @JsonProperty("data_scope") DataScope dataScope,
      @JsonProperty("role_active") boolean roleActive,
      @JsonProperty("authorization_version") long authorizationVersion) {}

  public record Decision(
      boolean allowed,
      @JsonProperty("data_scope") DataScope dataScope,
      List<Source> sources,
      List<AuthorizationEvaluator.Reason> reasons) {
    public Decision {
      sources = List.copyOf(sources);
      reasons = List.copyOf(reasons);
    }
  }

  public record EffectivePermissions(
      String view,
      @JsonProperty("as_of") Instant asOf,
      @JsonProperty("user_id") UUID userId,
      @JsonProperty("user_version") long userVersion,
      @JsonProperty("catalog_version") long catalogVersion,
      Map<String, Decision> permissions) {
    public EffectivePermissions {
      permissions = Map.copyOf(permissions);
    }
  }

  private record Access(
      UUID tenant, UUID actor, long catalogVersion, List<UserManagementMapper.UserRow> actorRows) {}

  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final RolePermissionMapper roles;
  private final ManagementQueryMapper queries;
  private final TenantManagementAuthorization authorization;
  private final AuthorizationRepository authorizationData;
  private final AuthorizationEvaluator evaluator;
  private final ManagementAudits audits;
  private final Clock clock;

  public TenantManagementQueryService(
      TenantTransactions transactions,
      UserManagementMapper users,
      RolePermissionMapper roles,
      ManagementQueryMapper queries,
      TenantManagementAuthorization authorization,
      AuthorizationRepository authorizationData,
      AuthorizationEvaluator evaluator,
      ManagementAudits audits,
      Clock clock) {
    this.transactions = transactions;
    this.users = users;
    this.roles = roles;
    this.queries = queries;
    this.authorization = authorization;
    this.authorizationData = authorizationData;
    this.evaluator = evaluator;
    this.audits = audits;
    this.clock = clock;
  }

  public Page<User> users(int page, int size, String query) {
    int offset = offset(page, size);
    if (query == null || query.length() > 100) throw invalid();
    return authorized(
        "identity:user:read",
        null,
        access -> {
          var rows = queries.users(access.tenant(), query.strip(), offset, size + 1);
          return page(page, size, rows.stream().map(TenantManagementQueryService::user).toList());
        });
  }

  public User user(UUID target) {
    return authorized("identity:user:read", target, access -> user(target(access, target)));
  }

  public UserRoles userRoles(UUID target) {
    return authorized(
        "identity:user:read",
        target,
        access -> {
          var user = target(access, target);
          var items =
              roles.memberRoles(access.tenant(), target).stream()
                  .sorted()
                  .map(id -> role(access, queries.role(access.tenant(), id)))
                  .toList();
          return new UserRoles(target, user.version(), items);
        });
  }

  public RolePage roles(int page, int size, UUID assignableTo) {
    return roles(page, size, assignableTo, "");
  }

  public RolePage roles(int page, int size, UUID assignableTo, String query) {
    int offset = offset(page, size);
    if (query == null || query.length() > 100) throw invalid();
    return authorized(
        assignableTo == null ? "identity:role:read" : "identity:role:assign",
        assignableTo,
        access -> {
          if (assignableTo != null) target(access, assignableTo);
          var rows = queries.roles(access.tenant(), query.strip(), offset, size + 1);
          // 可操作快照仅供展示，提交时仍须重查管理员与对象版本。
          var result = page(page, size, rows.stream().map(row -> role(access, row)).toList());
          return new RolePage(
              result.page(),
              result.size(),
              result.hasMore(),
              result.items(),
              managesRoleMetadata(access));
        });
  }

  public Page<User> roleMembers(UUID id, int page, int size) {
    int offset = offset(page, size);
    return authorized(
        "identity:role:read",
        id,
        access -> {
          if (id == null || queries.role(access.tenant(), id) == null) throw missing();
          authorization.actorFor("identity:user:read");
          authorization.requireProtectedAdministrator(
              access.tenant(), access.actor(), "identity:user:read", access.actorRows());
          var rows = queries.roleMembers(access.tenant(), id, offset, size + 1);
          return page(page, size, rows.stream().map(TenantManagementQueryService::user).toList());
        });
  }

  public RolePermissions rolePermissions(UUID id) {
    return authorized(
        "identity:role:read",
        id,
        access -> {
          var row = queries.role(access.tenant(), id);
          if (row == null) throw missing();
          return new RolePermissions(
              role(access, row), access.catalogVersion(), roleGrants(access.tenant(), id));
        });
  }

  public Catalog permissions(boolean assignable, UUID roleId) {
    return authorized(
        "identity:permission:read",
        roleId,
        access -> {
          if (assignable) {
            if (roleId == null) throw invalid();
            var row = queries.role(access.tenant(), roleId);
            if (row == null) throw missing();
            if (!role(access, row).canEdit())
              throw new PlatformException("MANAGEMENT_NOT_ALLOWED", "当前角色不可编辑", 403);
          }
          var entries = new ArrayList<Permission>();
          for (var definition : queries.permissions()) {
            var supported = scopes(definition.scopes());
            var allowed = new HashSet<>(supported);
            entries.add(
                new Permission(
                    definition.code(),
                    definition.name(),
                    definition.ownerService(),
                    definition.groupCode(),
                    definition.groupName(),
                    definition.resourceCode(),
                    definition.resourceName(),
                    definition.actionCode(),
                    definition.description(),
                    definition.sort(),
                    definition.status(),
                    definition.oauthScope(),
                    supported,
                    assignable ? allowed : Set.of(),
                    assignable
                        && definition.status().equals("ACTIVE")
                        && !definition.code().startsWith("identity:")));
          }
          return new Catalog(access.catalogVersion(), entries);
        });
  }

  public EffectivePermissions effectivePermissions(UUID target) {
    return authorized(
        "identity:permission:read",
        target,
        access -> {
          var user = target(access, target);
          var evaluated =
              evaluator.evaluate(
                  authorizationData.permissions(),
                  authorizationData.grants(access.tenant(), target),
                  null);
          var decisions = new TreeMap<String, Decision>();
          evaluated
              .decisions()
              .forEach(
                  (code, value) ->
                      decisions.put(
                          code,
                          new Decision(
                              value.allowed(),
                              value.dataScope(),
                              value.sources().stream()
                                  .map(
                                      source ->
                                          new Source(
                                              source.roleId(),
                                              source.dataScope(),
                                              source.roleActive(),
                                              source.authorizationVersion()))
                                  .toList(),
                              value.reasons())));
          return new EffectivePermissions(
              "ROLE_GRANTS",
              evaluated.asOf(),
              target,
              user.version(),
              access.catalogVersion(),
              decisions);
        });
  }

  private <T> T authorized(String operation, UUID target, Function<Access, T> action) {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        target,
        () -> {
          UUID actor = authorization.actorFor(operation);
          return transactions.execute(
              () -> {
                UUID tenant = CallContexts.current().tenantId();
                long catalog = users.lockCatalog();
                var row = users.lockTenant(tenant);
                if (row == null
                    || !row.status().equals("ACTIVE")
                    || row.expiresAt() != null && !clock.instant().isBefore(row.expiresAt()))
                  throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                users.lockMemberRoles(tenant, List.of(actor));
                var accounts = users.lockUsers(tenant, List.of(actor));
                authorization.requireProtectedAdministrator(tenant, actor, operation, accounts);
                // 查询在目录/租户锁内取一致快照；其他对象只读，不在账号锁之后逆序申请角色锁。
                return action.apply(new Access(tenant, actor, catalog, accounts));
              });
        });
  }

  private ManagementQueryMapper.UserRow target(Access access, UUID id) {
    if (id == null) throw invalid();
    var user = queries.user(access.tenant(), id);
    if (user == null) throw missing();
    return user;
  }

  private Role role(Access access, ManagementQueryMapper.RoleRow row) {
    if (row == null) throw missing();
    var restrictions = new ArrayList<String>();
    boolean assign =
        permitted(
            () -> {
              authorization.actorFor("identity:role:assign");
              authorization.requireProtectedAdministrator(
                  access.tenant(), access.actor(), "identity:role:assign", access.actorRows());
            },
            restrictions);
    boolean edit =
        permitted(
            () -> {
              authorization.actorFor("identity:role:manage");
              authorization.requireProtectedAdministrator(
                  access.tenant(), access.actor(), "identity:role:manage", access.actorRows());
              if (row.protectedRole())
                throw new PlatformException("PROTECTED_ROLE", "内置角色不能修改", 403);
            },
            restrictions);
    boolean active = row.status().equals("ACTIVE");
    if (assign && !active) restrictions.add("ROLE_NOT_ASSIGNABLE");
    return new Role(
        row.id(),
        row.code(),
        row.name(),
        row.status(),
        row.protectedRole(),
        row.version(),
        row.authorizationVersion(),
        assign && active,
        edit,
        assign,
        !row.protectedRole() && managesRoleMetadata(access),
        restrictions.stream().distinct().toList(),
        row.description());
  }

  private boolean managesRoleMetadata(Access access) {
    return permitted(
        () -> {
          authorization.actorFor("identity:role:manage");
          authorization.requireProtectedAdministrator(
              access.tenant(), access.actor(), "identity:role:manage", access.actorRows());
        },
        new ArrayList<>());
  }

  private static boolean permitted(Runnable check, List<String> restrictions) {
    try {
      check.run();
      return true;
    } catch (PlatformException failure) {
      if (failure.status() != 400 && failure.status() != 403 && failure.status() != 409)
        throw failure;
      restrictions.add(failure.code());
      return false;
    }
  }

  private List<RolePermissionService.Grant> roleGrants(UUID tenant, UUID role) {
    return roles.grants(tenant, role).stream()
        .map(
            value ->
                new RolePermissionService.Grant(
                    value.permissionCode(),
                    value.dataScope() == null ? null : DataScope.valueOf(value.dataScope())))
        .toList();
  }

  private static User user(ManagementQueryMapper.UserRow row) {
    return new User(
        row.id(),
        row.username(),
        row.displayName(),
        row.status(),
        row.mustChangePassword(),
        row.version());
  }

  private static Set<DataScope> scopes(String value) {
    return value == null || value.isEmpty()
        ? Set.of()
        : Arrays.stream(value.split(","))
            .map(DataScope::valueOf)
            .collect(Collectors.toUnmodifiableSet());
  }

  private static int offset(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid();
    return page * size;
  }

  private static <T> Page<T> page(int page, int size, List<T> rows) {
    return new Page<>(page, size, rows.size() > size, rows.stream().limit(size).toList());
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_ARGUMENT", "分页、筛选或目标参数不合法", 400);
  }

  private static PlatformException missing() {
    return new PlatformException("NOT_FOUND", "目标不存在或不可见", 404);
  }
}
