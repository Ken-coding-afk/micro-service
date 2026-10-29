package com.ken.platform.identity.service.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.group.UserGroupRepository;
import com.ken.platform.identity.dal.mapper.group.UserGroupRepository.Group;
import com.ken.platform.identity.dal.mapper.role.RoleManagementMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class UserGroupService {
  public record Reference(UUID id, Long version) {}

  public record Page<T>(
      int page,
      int size,
      @JsonProperty("has_more") boolean hasMore,
      @JsonProperty("organization_version") long organizationVersion,
      List<T> items) {}

  public record UserGroups(
      @JsonProperty("user_id") UUID userId, long version, List<Group> groups) {}

  public record GroupRoles(
      @JsonProperty("group_id") UUID groupId, long version, List<UserGroupRepository.Role> roles) {}

  public record Deleted(UUID id, @JsonProperty("organization_version") long organizationVersion) {}

  private record Access(UUID tenant, UUID actor) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final UserGroupRepository groups;
  private final UserManagementMapper users;
  private final RoleManagementMapper auditRows;
  private final AccountManagementMapper requests;
  private final TenantManagementAuthorization authorization;
  private final TenantTransactions transactions;
  private final ManagementAudits audits;
  private final Clock clock;

  public UserGroupService(
      UserGroupRepository groups,
      UserManagementMapper users,
      RoleManagementMapper auditRows,
      AccountManagementMapper requests,
      TenantManagementAuthorization authorization,
      TenantTransactions transactions,
      ManagementAudits audits,
      Clock clock) {
    this.groups = groups;
    this.users = users;
    this.auditRows = auditRows;
    this.requests = requests;
    this.authorization = authorization;
    this.transactions = transactions;
    this.audits = audits;
    this.clock = clock;
  }

  public Page<Group> list(UUID parent, String query, int page, int size) {
    int offset = offset(page, size);
    String search = search(query);
    return authorized(
        "read",
        ManagementAudits.Action.API_REQUEST,
        null,
        a -> {
          if (parent != null) required(a.tenant(), parent);
          return page(
              page, size, a.tenant(), groups.list(a.tenant(), parent, search, offset, size + 1));
        });
  }

  public Group get(UUID id) {
    return authorized(
        "read", ManagementAudits.Action.API_REQUEST, id, a -> required(a.tenant(), id));
  }

  public Group create(
      String code,
      String name,
      String description,
      UUID parent,
      int sort,
      Long organizationVersion) {
    if (code == null || !code.matches("[a-z][a-z0-9_-]{0,63}") || sort < 0)
      throw invalid("用户组编码或排序不合法");
    String title = text(name, 128, true), details = text(description, 1000, false);
    long expected = version(organizationVersion);
    return authorized(
        "manage",
        ManagementAudits.Action.GROUP_CREATE,
        null,
        a -> {
          String digest =
              digest(
                  List.of(
                      code,
                      title,
                      details,
                      parent == null ? "" : parent.toString(),
                      sort,
                      expected));
          var prior =
              requests.prior(
                  authorization.actorType(),
                  a.actor(),
                  a.tenant(),
                  "identity.group.created",
                  CallContexts.current().requestId());
          if (prior != null) {
            if (!prior.digest().equals(digest))
              throw conflict("REQUEST_ID_CONFLICT", "相同请求标识不能用于不同创建参数");
            return required(
                a.tenant(), UUID.fromString(JSON.readTree(prior.result()).get("id").asString()));
          }
          treeVersion(a.tenant(), expected);
          activeParent(a.tenant(), parent);
          UUID id = UUID.randomUUID();
          groups.create(a.tenant(), id, parent, code, title, details, sort);
          groups.advanceTree(a.tenant());
          requests.remember(
              authorization.actorType(),
              a.actor(),
              a.tenant(),
              "identity.group.created",
              CallContexts.current().requestId(),
              digest,
              JSON.writeValueAsString(Map.of("id", id)));
          Group result = required(a.tenant(), id);
          audit(a, "identity.group.created", id, Map.of("after", result));
          return result;
        });
  }

  public Group update(
      UUID id,
      Long expectedVersion,
      Long organizationVersion,
      String name,
      String description,
      int sort,
      String status) {
    long expected = version(expectedVersion), tree = version(organizationVersion);
    String title = text(name, 128, true), details = text(description, 1000, false);
    if (sort < 0 || !Set.of("ACTIVE", "DISABLED").contains(status == null ? "" : status))
      throw invalid("用户组状态或排序不合法");
    return authorized(
        "manage",
        ManagementAudits.Action.GROUP_UPDATE,
        id,
        a -> {
          treeVersion(a.tenant(), tree);
          Group before = locked(a.tenant(), id, expected);
          if (status.equals("ACTIVE") && !before.status().equals("ACTIVE"))
            activeParent(a.tenant(), before.parentId());
          groups.update(a.tenant(), id, title, details, sort, status);
          groups.advanceTree(a.tenant());
          Group result = required(a.tenant(), id);
          audit(a, "identity.group.updated", id, Map.of("before", before, "after", result));
          return result;
        });
  }

  public Group move(
      UUID id, Long expectedVersion, Long organizationVersion, UUID parent, int sort) {
    long expected = version(expectedVersion), tree = version(organizationVersion);
    if (sort < 0) throw invalid("排序必须为非负整数");
    return authorized(
        "manage",
        ManagementAudits.Action.GROUP_MOVE,
        id,
        a -> {
          treeVersion(a.tenant(), tree);
          Group before = locked(a.tenant(), id, expected);
          activeParent(a.tenant(), before.parentId());
          activeParent(a.tenant(), parent);
          if (parent != null && groups.descendant(a.tenant(), id, parent))
            throw conflict("GROUP_CYCLE", "不能移动到自身或下级组织");
          groups.move(a.tenant(), id, parent, sort);
          groups.advanceTree(a.tenant());
          Group result = required(a.tenant(), id);
          audit(a, "identity.group.moved", id, Map.of("before", before, "after", result));
          return result;
        });
  }

  public Deleted delete(UUID id, Long expectedVersion, Long organizationVersion) {
    long expected = version(expectedVersion), tree = version(organizationVersion);
    return authorized(
        "manage",
        ManagementAudits.Action.GROUP_DELETE,
        id,
        a -> {
          treeVersion(a.tenant(), tree);
          Group before = locked(a.tenant(), id, expected);
          if (before.hasChildren() || before.memberCount() > 0 || before.roleCount() > 0)
            throw conflict(
                "GROUP_NOT_EMPTY",
                "请先移走下级组织（"
                    + before.descendantCount()
                    + "）、成员（"
                    + before.memberCount()
                    + "）及角色（"
                    + before.roleCount()
                    + "）");
          groups.delete(a.tenant(), id);
          groups.advanceTree(a.tenant());
          audit(a, "identity.group.deleted", id, Map.of("before", before));
          return new Deleted(id, groups.organizationVersion(a.tenant()));
        });
  }

  public Page<UserGroupRepository.Member> members(
      UUID id, boolean descendants, String query, int page, int size) {
    int offset = offset(page, size);
    String search = search(query);
    return authorized(
        "read",
        ManagementAudits.Action.API_REQUEST,
        id,
        a -> {
          authorization.actorFor("identity:user:read");
          required(a.tenant(), id);
          return page(
              page,
              size,
              a.tenant(),
              groups.members(a.tenant(), id, descendants, search, offset, size + 1));
        });
  }

  public Group changeMembers(UUID id, Long groupVersion, List<Reference> requested, boolean add) {
    long expected = version(groupVersion);
    List<Reference> refs = references(requested, 100, false);
    return authorized(
        "assign",
        ManagementAudits.Action.GROUP_MEMBERS,
        id,
        a -> {
          Group before = locked(a.tenant(), id, expected);
          if (add) active(before);
          var accounts =
              users.lockUsers(a.tenant(), refs.stream().map(Reference::id).toList()).stream()
                  .collect(Collectors.toMap(UserManagementMapper.UserRow::id, Function.identity()));
          for (var ref : refs) {
            var user = accounts.get(ref.id());
            if (user == null) throw missing();
            if (user.version() != ref.version())
              throw conflict("USER_VERSION_CONFLICT", "成员账号已变化，请刷新");
            if (add && user.status().equals("DISABLED"))
              throw conflict("USER_UNAVAILABLE", "停用账号不能加入用户组");
            if (!add && !groups.member(a.tenant(), id, ref.id()))
              throw conflict("GROUP_MEMBER_NOT_DIRECT", "该用户不是此组的直接成员，请在实际所属组移除");
          }
          var changed = new ArrayList<UUID>();
          for (var ref : refs)
            if (groups.member(a.tenant(), id, ref.id()) != add) {
              groups.membership(a.tenant(), id, ref.id(), add);
              changed.add(ref.id());
            }
          if (!changed.isEmpty()) {
            groups.touch(a.tenant(), id);
            audit(
                a,
                "identity.group.members",
                id,
                Map.of("added", add, "users", changed, "version", before.version() + 1));
          }
          return required(a.tenant(), id);
        });
  }

  public GroupRoles roles(UUID id) {
    return authorized(
        "read",
        ManagementAudits.Action.API_REQUEST,
        id,
        a -> {
          authorization.actorFor("identity:role:read");
          Group group = required(a.tenant(), id);
          return new GroupRoles(id, group.version(), groups.roles(a.tenant(), id));
        });
  }

  public GroupRoles replaceRoles(UUID id, Long groupVersion, List<Reference> requested) {
    long expected = version(groupVersion);
    List<Reference> refs = references(requested, 1000, true);
    return authorized(
        "assign",
        ManagementAudits.Action.GROUP_ROLES,
        id,
        a -> {
          Group before = locked(a.tenant(), id, expected);
          var old =
              groups.roles(a.tenant(), id).stream()
                  .map(UserGroupRepository.Role::id)
                  .collect(Collectors.toSet());
          var next = refs.stream().map(Reference::id).collect(Collectors.toSet());
          Set<UUID> all = new HashSet<>(old);
          all.addAll(next);
          var roles =
              groups.lockRoles(a.tenant(), all.stream().toList()).stream()
                  .collect(Collectors.toMap(UserGroupRepository.Role::id, Function.identity()));
          for (var ref : refs) {
            var role = roles.get(ref.id());
            if (role == null) throw missing();
            if (role.version() != ref.version())
              throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请刷新");
            if (role.protectedRole())
              throw new PlatformException("PROTECTED_ROLE", "用户组只能关联普通业务角色", 403);
            if (!old.contains(role.id())) {
              active(before);
              if (!role.status().equals("ACTIVE"))
                throw conflict("ROLE_NOT_ASSIGNABLE", "不能新增停用角色");
            }
          }
          for (UUID role : all)
            if (old.contains(role) != next.contains(role))
              groups.roleAssignment(a.tenant(), id, role, next.contains(role));
          if (!old.equals(next)) {
            groups.touch(a.tenant(), id);
            audit(a, "identity.group.roles", id, Map.of("before", old, "after", next));
          }
          return new GroupRoles(
              id, required(a.tenant(), id).version(), groups.roles(a.tenant(), id));
        });
  }

  public UserGroups userGroups(UUID id) {
    return authorized(
        "read",
        ManagementAudits.Action.API_REQUEST,
        id,
        a -> {
          authorization.actorFor("identity:user:read");
          var user = users.findUser(a.tenant(), id);
          if (user == null) throw missing();
          return new UserGroups(id, user.version(), groups.userGroups(a.tenant(), id));
        });
  }

  public UserGroups replaceUserGroups(UUID id, Long userVersion, List<Reference> requested) {
    long expected = version(userVersion);
    List<Reference> refs = references(requested, 1000, true);
    return authorized(
        "assign",
        ManagementAudits.Action.GROUP_MEMBERS,
        id,
        a -> {
          authorization.actorFor("identity:user:manage");
          var previous = groups.userGroups(a.tenant(), id);
          var old = previous.stream().map(Group::id).collect(Collectors.toSet());
          var next = refs.stream().map(Reference::id).collect(Collectors.toSet());
          var all = new HashSet<>(old);
          all.addAll(next);
          groups.lock(a.tenant(), all.stream().toList());
          for (var ref : refs) {
            var group = required(a.tenant(), ref.id());
            if (group.version() != ref.version())
              throw conflict("GROUP_VERSION_CONFLICT", "用户组已变化，请刷新");
            if (!old.contains(ref.id())) active(group);
          }
          var accounts = users.lockUsers(a.tenant(), List.of(id));
          if (accounts.isEmpty()) throw missing();
          var user = accounts.getFirst();
          if (user.version() != expected) throw conflict("USER_VERSION_CONFLICT", "用户已变化，请刷新");
          if (user.status().equals("DISABLED") && !old.containsAll(next))
            throw conflict("USER_UNAVAILABLE", "停用账号不能加入用户组");
          for (UUID group : all)
            if (old.contains(group) != next.contains(group)) {
              groups.membership(a.tenant(), group, id, next.contains(group));
              groups.touch(a.tenant(), group);
            }
          if (!old.equals(next))
            audit(a, "identity.user.groups", id, Map.of("before", old, "after", next));
          return new UserGroups(
              id, users.findUser(a.tenant(), id).version(), groups.userGroups(a.tenant(), id));
        });
  }

  public Page<Group> roleGroups(UUID role, int page, int size) {
    int offset = offset(page, size);
    return authorized(
        "read",
        ManagementAudits.Action.API_REQUEST,
        role,
        a -> {
          authorization.actorFor("identity:role:read");
          if (auditRows.details(a.tenant(), role) == null) throw missing();
          return page(
              page, size, a.tenant(), groups.roleGroups(a.tenant(), role, offset, size + 1));
        });
  }

  private <T> T authorized(
      String operation, ManagementAudits.Action event, UUID target, Function<Access, T> action) {
    return audits.attempt(
        event,
        target,
        () -> {
          UUID actor = authorization.actorFor("identity:group:" + operation);
          try {
            return authorization.execute(
                transactions,
                () -> {
                  UUID tenant = authorization.tenantId();
                  authorization.lockCatalog();
                  var row = authorization.lockTenant(tenant);
                  if (row == null
                      || !row.status().equals("ACTIVE")
                      || row.expiresAt() != null && !clock.instant().isBefore(row.expiresAt()))
                    throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                  // 目录和租户锁稳定管理资格；先读资格，避免在组织锁之前反向取得账号锁。
                  var acting =
                      TenantManagementAuthorization.targetTenant() == null
                          ? users.findUser(tenant, actor)
                          : null;
                  authorization.requireProtectedAdministrator(
                      tenant,
                      actor,
                      "identity:group:" + operation,
                      acting == null ? List.of() : List.of(acting));
                  return action.apply(new Access(tenant, actor));
                });
          } catch (DuplicateKeyException duplicate) {
            throw conflict("GROUP_CODE_CONFLICT", "当前租户的用户组编码已存在");
          }
        });
  }

  private Group required(UUID tenant, UUID id) {
    Group group = groups.get(tenant, id);
    if (group == null) throw missing();
    return group;
  }

  private Group locked(UUID tenant, UUID id, long version) {
    groups.lock(tenant, List.of(id));
    Group group = required(tenant, id);
    if (group.version() != version) throw conflict("GROUP_VERSION_CONFLICT", "用户组已变化，请刷新");
    return group;
  }

  private void activeParent(UUID tenant, UUID parent) {
    if (parent != null) active(required(tenant, parent));
  }

  private static void active(Group group) {
    if (!group.effectiveStatus().equals("ACTIVE"))
      throw conflict("GROUP_UNAVAILABLE", "用户组或上级组织已停用");
  }

  private void treeVersion(UUID tenant, long expected) {
    if (groups.organizationVersion(tenant) != expected)
      throw conflict("ORGANIZATION_VERSION_CONFLICT", "组织结构已变化，请刷新");
  }

  private void audit(Access a, String action, UUID target, Object details) {
    auditRows.audit(
        a.actor(),
        authorization.actorType(),
        a.tenant(),
        action,
        target.toString(),
        CallContexts.current().requestId(),
        JSON.writeValueAsString(details));
  }

  private <T> Page<T> page(int page, int size, UUID tenant, List<T> rows) {
    return new Page<>(
        page,
        size,
        rows.size() > size,
        groups.organizationVersion(tenant),
        rows.stream().limit(size).toList());
  }

  private static int offset(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("分页参数不合法");
    return page * size;
  }

  private static String search(String query) {
    return query == null ? "" : text(query, 128, false);
  }

  public static List<Reference> references(List<Reference> values, int max, boolean empty) {
    if (values == null || values.size() > max || !empty && values.isEmpty())
      throw invalid("关联集合数量不合法");
    var ids = new HashSet<UUID>();
    for (var ref : values)
      if (ref == null
          || ref.id() == null
          || ref.version() == null
          || ref.version() < 0
          || !ids.add(ref.id())) throw invalid("关联对象或版本不合法/重复");
    return List.copyOf(values);
  }

  private static long version(Long value) {
    if (value == null || value < 0) throw invalid("缺少有效版本");
    return value;
  }

  private static String text(String value, int max, boolean required) {
    String clean = value == null ? "" : value.strip();
    if (required && clean.isEmpty()
        || clean.codePointCount(0, clean.length()) > max
        || clean
            .codePoints()
            .anyMatch(
                c ->
                    Character.isISOControl(c)
                        || c == '<'
                        || c == '>'
                        || c >= 0xD800 && c <= 0xDFFF)) throw invalid("名称或说明不合法");
    return clean;
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

  private static PlatformException missing() {
    return new PlatformException("NOT_FOUND", "对象不存在或不可见", 404);
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("INVALID_ARGUMENT", message, 400);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
