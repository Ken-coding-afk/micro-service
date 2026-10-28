package com.ken.platform.identity.service.role;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.role.RoleManagementMapper;
import com.ken.platform.identity.dal.mapper.role.RolePermissionMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class RoleManagementService {
  public enum Status {
    ACTIVE,
    DISABLED
  }

  public record View(
      UUID id,
      String code,
      String name,
      Status status,
      @JsonProperty("protected") boolean protectedRole,
      long version,
      @JsonProperty("authorization_version") long authorizationVersion,
      String description) {}

  private record Locked(long catalogVersion, RoleManagementMapper.RoleDetails role) {}

  private static final String OPERATION = "identity:role:manage";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ManagementAudits audits;
  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final RolePermissionMapper permissions;
  private final RoleManagementMapper roles;
  private final TenantManagementAuthorization authorization;
  private final Clock clock;

  public RoleManagementService(
      TenantTransactions transactions,
      UserManagementMapper users,
      RolePermissionMapper permissions,
      RoleManagementMapper roles,
      TenantManagementAuthorization authorization,
      Clock clock,
      ManagementAudits audits) {
    this.audits = audits;
    this.transactions = transactions;
    this.users = users;
    this.permissions = permissions;
    this.roles = roles;
    this.authorization = authorization;
    this.clock = clock;
  }

  public View create(String code, String name) {
    return create(code, name, "");
  }

  public View create(String code, String name, String description) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_CREATE, null, () -> createInternal(code, name, description));
  }

  private View createInternal(String code, String name, String description) {
    validateCode(code);
    String cleanName = validateName(name);
    String cleanDescription = description == null ? "" : validateDescription(description);
    UUID actor = authorization.actorFor(OPERATION);
    try {
      return transactions.execute(
          () -> {
            lock(actor, null);
            UUID tenant = CallContexts.current().tenantId();
            UUID id = UUID.randomUUID();
            roles.create(id, tenant, code, cleanName, "ACTIVE", cleanDescription);
            audit(
                actor,
                "identity.role.created",
                id,
                Map.of(
                    "code",
                    code,
                    "name",
                    cleanName,
                    "status",
                    "ACTIVE",
                    "description",
                    cleanDescription));
            return new View(id, code, cleanName, Status.ACTIVE, false, 0, 0, cleanDescription);
          });
    } catch (DuplicateKeyException duplicate) {
      throw conflict("ROLE_CODE_CONFLICT", "角色编码已存在");
    }
  }

  public View update(UUID id, long expectedVersion, String name, Status status) {
    return update(id, expectedVersion, name, status, null);
  }

  public View update(
      UUID id, long expectedVersion, String name, Status status, String description) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_UPDATE,
        id,
        () -> updateInternal(id, expectedVersion, name, status, description));
  }

  private View updateInternal(
      UUID id, long expectedVersion, String name, Status status, String description) {
    String cleanName = validateName(name);
    String requestedDescription = description == null ? null : validateDescription(description);
    if (status == null) {
      throw invalid("角色属性参数不合法");
    }
    UUID actor = authorization.actorFor(OPERATION);
    return transactions.execute(
        () -> {
          var locked = lock(actor, id);
          var role = locked.role();
          String cleanDescription =
              requestedDescription == null ? role.description() : requestedDescription;
          mutable(role);
          if (role.version() != expectedVersion) {
            throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请刷新后操作");
          }
          if (role.name().equals(cleanName)
              && role.status().equals(status.name())
              && role.description().equals(cleanDescription)) {
            return view(role);
          }
          if (roles.update(
                  CallContexts.current().tenantId(),
                  id,
                  expectedVersion,
                  cleanName,
                  status.name(),
                  cleanDescription)
              != 1) {
            throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请刷新后操作");
          }
          audit(
              actor,
              "identity.role.updated",
              id,
              Map.of(
                  "before",
                  Map.of(
                      "name",
                      role.name(),
                      "status",
                      role.status(),
                      "description",
                      role.description()),
                  "after",
                  Map.of(
                      "name", cleanName, "status", status.name(), "description", cleanDescription),
                  "version",
                  role.version() + 1));
          return new View(
              id,
              role.code(),
              cleanName,
              status,
              false,
              role.version() + 1,
              role.authorizationVersion() + (role.status().equals(status.name()) ? 0 : 1),
              cleanDescription);
        });
  }

  public View copy(
      UUID source, long expectedVersion, long expectedCatalogVersion, String code, String name) {
    return copy(source, expectedVersion, expectedCatalogVersion, code, name, null);
  }

  public View copy(
      UUID source,
      long expectedVersion,
      long expectedCatalogVersion,
      String code,
      String name,
      String description) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_COPY,
        source,
        () ->
            copyInternal(source, expectedVersion, expectedCatalogVersion, code, name, description));
  }

  private View copyInternal(
      UUID source,
      long expectedVersion,
      long expectedCatalogVersion,
      String code,
      String name,
      String description) {
    validateCode(code);
    String cleanName = validateName(name);
    String requestedDescription = description == null ? null : validateDescription(description);
    UUID actor = authorization.actorFor(OPERATION);
    try {
      return transactions.execute(
          () -> {
            var locked = lock(actor, source);
            var role = locked.role();
            String cleanDescription =
                requestedDescription == null ? role.description() : requestedDescription;
            mutable(role);
            if (role.version() != expectedVersion
                || locked.catalogVersion() != expectedCatalogVersion) {
              throw conflict("ROLE_OR_CATALOG_VERSION_CONFLICT", "角色或目录已变化，请刷新后复制");
            }
            UUID tenant = CallContexts.current().tenantId();
            var grants = permissions.grants(tenant, source);
            var catalog =
                permissions.catalog().stream()
                    .collect(
                        Collectors.toMap(RolePermissionMapper.PermissionRow::code, value -> value));
            // 复制不静默裁剪，也不能把历史只读权限当成新授权。
            for (var grant : grants) {
              var permission = catalog.get(grant.permissionCode());
              List<String> scopes =
                  permission == null || permission.scopes() == null || permission.scopes().isEmpty()
                      ? List.of()
                      : Arrays.asList(permission.scopes().split(","));
              if (permission == null
                  || !permission.principalType().equals("TENANT")
                  || !permission.status().equals("ACTIVE")
                  || (grant.dataScope() == null
                      ? !scopes.isEmpty()
                      : !scopes.contains(grant.dataScope()))) {
                throw invalid("源角色包含不能重新授出的权限，请先处理源角色授权");
              }
            }
            UUID id = UUID.randomUUID();
            roles.create(id, tenant, code, cleanName, role.status(), cleanDescription);
            for (var grant : grants) {
              permissions.addGrant(tenant, id, grant.permissionCode(), grant.dataScope());
            }
            audit(
                actor,
                "identity.role.copied",
                id,
                Map.of(
                    "source_id",
                    source.toString(),
                    "source_version",
                    role.version(),
                    "code",
                    code,
                    "name",
                    cleanName,
                    "description",
                    cleanDescription,
                    "status",
                    role.status(),
                    "permissions",
                    grants.stream()
                        .map(
                            grant ->
                                new RolePermissionService.Grant(
                                    grant.permissionCode(),
                                    grant.dataScope() == null
                                        ? null
                                        : DataScope.valueOf(grant.dataScope())))
                        .toList()));
            return new View(
                id, code, cleanName, Status.valueOf(role.status()), false, 0, 0, cleanDescription);
          });
    } catch (DuplicateKeyException duplicate) {
      throw conflict("ROLE_CODE_CONFLICT", "角色编码已存在");
    }
  }

  public record Deleted(UUID id, boolean deleted) {}

  public Deleted delete(UUID id, long expectedVersion) {
    return audits.attempt(
        ManagementAudits.Action.ROLE_DELETE,
        id,
        () -> {
          UUID actor = authorization.actorFor(OPERATION);
          return transactions.execute(
              () -> {
                var role = lock(actor, id).role();
                mutable(role);
                if (role.version() != expectedVersion)
                  throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请核对后删除");
                UUID tenant = CallContexts.current().tenantId();
                if (roles.hasMembers(tenant, id)) throw conflict("ROLE_NOT_EMPTY", "请先移除此角色的全部成员");
                if (roles.hasDelegationReferences(tenant, id))
                  throw conflict("ROLE_REFERENCED", "角色仍有管理委派引用，不能删除或抹去委派历史");
                var previous = permissions.grants(tenant, id);
                roles.clearPermissions(tenant, id);
                if (roles.delete(tenant, id, expectedVersion) != 1)
                  throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请核对后删除");
                audit(
                    actor,
                    "identity.role.deleted",
                    id,
                    Map.of(
                        "code",
                        role.code(),
                        "name",
                        role.name(),
                        "description",
                        role.description(),
                        "version",
                        role.version(),
                        "permissions",
                        previous.stream()
                            .map(
                                grant ->
                                    new RolePermissionService.Grant(
                                        grant.permissionCode(),
                                        grant.dataScope() == null
                                            ? null
                                            : DataScope.valueOf(grant.dataScope())))
                            .toList()));
                return new Deleted(id, true);
              });
        });
  }

  private Locked lock(UUID actor, UUID target) {
    var context = CallContexts.current();
    UUID tenant = context.tenantId();
    long catalog = users.lockCatalog();
    var current = users.lockTenant(tenant);
    if (current == null
        || !current.status().equals("ACTIVE")
        || current.expiresAt() != null && !clock.instant().isBefore(current.expiresAt())) {
      throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
    }
    var ids = new HashSet<>(permissions.memberRoles(tenant, actor));
    if (target != null) {
      ids.add(target);
    }
    if (!ids.isEmpty()) {
      permissions.lockRoles(tenant, List.copyOf(ids));
    }
    var accounts = users.lockUsers(tenant, List.of(actor));
    authorization.requireProtectedAdministrator(tenant, actor, OPERATION, accounts);
    var role = target == null ? null : roles.details(tenant, target);
    if (target != null && role == null) {
      throw new PlatformException("NOT_FOUND", "角色不存在或不可见", 404);
    }
    return new Locked(catalog, role);
  }

  private void audit(UUID actor, String action, UUID target, Map<String, ?> details) {
    var context = CallContexts.current();
    roles.audit(
        actor,
        context.tenantId(),
        action,
        target.toString(),
        context.requestId(),
        JSON.writeValueAsString(details));
  }

  private static View view(RoleManagementMapper.RoleDetails role) {
    return new View(
        role.id(),
        role.code(),
        role.name(),
        Status.valueOf(role.status()),
        role.protectedRole(),
        role.version(),
        role.authorizationVersion(),
        role.description());
  }

  private static void mutable(RoleManagementMapper.RoleDetails role) {
    if (role.protectedRole()) {
      throw new PlatformException("PROTECTED_ROLE", "内置角色不走普通编辑或复制入口", 403);
    }
  }

  private static void validateCode(String code) {
    if (code == null || !code.matches("[a-z][a-z0-9-]{0,127}")) {
      throw invalid("角色编码不合法");
    }
  }

  private static String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw invalid("角色名称不能为空");
    }
    String clean = name.strip();
    if (clean.codePointCount(0, clean.length()) > 128) {
      throw invalid("角色名称超过长度限制");
    }
    return clean;
  }

  private static String validateDescription(String value) {
    String clean = value.replace("\r\n", "\n");
    if (clean
        .codePoints()
        .anyMatch(
            cp ->
                cp == '<'
                    || cp == '>'
                    || Character.isISOControl(cp) && cp != '\n'
                    || cp >= 0xD800 && cp <= 0xDFFF))
      throw new PlatformException("ROLE_DESCRIPTION_INVALID", "角色说明只接受不含控制字符或尖括号的纯文本", 400);
    clean = clean.strip();
    if (clean.codePointCount(0, clean.length()) > 1024)
      throw new PlatformException("ROLE_DESCRIPTION_INVALID", "角色说明超过长度限制", 400);
    return clean;
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("ROLE_INVALID", message, 400);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
