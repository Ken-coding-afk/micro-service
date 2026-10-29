package com.ken.platform.identity.service.account;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.group.UserGroupRepository;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import com.ken.platform.identity.service.group.UserGroupService;
import com.ken.platform.identity.service.oauth.ClientManagementService;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.PasswordCredentials.OneTimeSecret;
import com.ken.platform.identity.service.security.RecentAuthentication;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.ken.platform.identity.service.user.UserProfileService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 账号只生成 PENDING_ACTIVATION；首次改密的原子激活由凭证流程执行。 */
@Service
public final class AccountCreationService {
  public record TenantCommand(
      String code, String name, Instant expiresAt, String adminUsername, String adminDisplayName) {}

  public record CreatedTenant(
      UUID tenantId, UUID adminId, OneTimeSecret credential, boolean replayed) {}

  public record UserCommand(
      String username,
      UserProfileService.Fields profile,
      List<UserGroupService.Reference> groups,
      List<UserGroupService.Reference> roles) {}

  public record CreatedAccount(UUID id, OneTimeSecret credential, boolean replayed) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String USER_CREATE = "identity.user.created";
  private static final String TENANT_CREATE = "platform.tenant.created";
  private static final String PLATFORM_CREATE = "platform.admin.created";
  private final ManagementAudits audits;
  private final TransactionTemplate controlTransactions;
  private final TenantTransactions tenantTransactions;
  private final AccountManagementMapper accounts;
  private final UserManagementMapper users;
  private final PlatformManagementAuthorization platform;
  private final TenantManagementAuthorization tenantAuthorization;
  private final SecurityPolicies policies;
  private final PasswordCredentials passwords;
  private final Clock clock;
  private final RecentAuthentication recent;
  private final ClientManagementService clients;
  private final UserGroupRepository groups;

  public AccountCreationService(
      PlatformTransactionManager manager,
      TenantTransactions tenantTransactions,
      AccountManagementMapper accounts,
      UserManagementMapper users,
      PlatformManagementAuthorization platform,
      TenantManagementAuthorization tenantAuthorization,
      SecurityPolicies policies,
      PasswordCredentials passwords,
      Clock clock,
      ManagementAudits audits,
      RecentAuthentication recent,
      ClientManagementService clients,
      UserGroupRepository groups) {
    this.audits = audits;
    this.controlTransactions = new TransactionTemplate(manager);
    this.tenantTransactions = tenantTransactions;
    this.accounts = accounts;
    this.users = users;
    this.platform = platform;
    this.tenantAuthorization = tenantAuthorization;
    this.policies = policies;
    this.passwords = passwords;
    this.clock = clock;
    this.recent = recent;
    this.clients = clients;
    this.groups = groups;
  }

  public CreatedTenant createTenant(TenantCommand command) {
    requireOwnTransaction();
    return audits.attempt(
        ManagementAudits.Action.TENANT_CREATE, null, () -> createTenantInternal(command));
  }

  private CreatedTenant createTenantInternal(TenantCommand command) {
    if (command == null
        || command.code() == null
        || !command.code().matches("[a-z][a-z0-9-]{0,63}")) {
      throw invalid();
    }
    String name = AccountFields.displayName(command.name());
    String username = AccountFields.username(command.adminUsername());
    String display = AccountFields.displayName(command.adminDisplayName());
    UUID actor = platform.actorFor("platform:tenant:manage");
    String digest =
        digest(
            Map.of(
                "code",
                command.code(),
                "name",
                name,
                "admin_username",
                username,
                "admin_display_name",
                display,
                "expires_at",
                command.expiresAt() == null ? "" : command.expiresAt().toString()));
    var issued = new AtomicReference<OneTimeSecret>();
    return guarded(
        issued,
        () -> {
          try {
            return controlTransactions.execute(
                status -> {
                  users.lockCatalog();
                  platform.requireActive(actor);
                  recent.require(null);
                  var previous = prior("PLATFORM", actor, null, TENANT_CREATE, digest);
                  if (previous != null) {
                    return new CreatedTenant(
                        UUID.fromString(previous.get("tenant_id").asString()),
                        UUID.fromString(previous.get("admin_id").asString()),
                        null,
                        true);
                  }
                  if (command.expiresAt() != null
                      && !clock.instant().isBefore(command.expiresAt())) {
                    throw invalid();
                  }
                  var corePermissions = BuiltinPermissionCatalog.PROTECTED_TENANT_PERMISSIONS;
                  if (accounts.activeCorePermissions(corePermissions) != corePermissions.size()) {
                    throw new PlatformException("IDENTITY_CATALOG_NOT_READY", "身份管理目录尚未就绪", 503);
                  }
                  UUID tenant = UUID.randomUUID();
                  UUID user = UUID.randomUUID();
                  UUID role = UUID.randomUUID();
                  accounts.createTenant(tenant, command.code(), name, command.expiresAt());
                  clients.enrollBootstrapTenant(tenant);
                  var material = passwords.temporary(policies.current(tenant));
                  issued.set(material.secret());
                  var context = CallContexts.current();
                  try (var ignored =
                      CallContexts.bind(
                          new CallContext(
                              context.requestId(),
                              tenant,
                              actor.toString(),
                              null,
                              context.traceparent(),
                              context.deadline()))) {
                    tenantTransactions.execute(
                        () -> {
                          accounts.createProtectedRole(role, tenant);
                          if (accounts.grantCorePermissions(role, tenant, corePermissions)
                              != corePermissions.size()) {
                            throw new PlatformException(
                                "IDENTITY_CATALOG_NOT_READY", "身份管理目录尚未就绪", 503);
                          }
                          accounts.createUser(user, tenant, username, display, material.hash());
                          accounts.initialMembership(tenant, user, role);
                          return null;
                        });
                  }
                  var result = Map.of("tenant_id", tenant.toString(), "admin_id", user.toString());
                  accounts.audit(
                      actor,
                      "PLATFORM",
                      tenant,
                      TENANT_CREATE,
                      tenant.toString(),
                      context.requestId(),
                      JSON.writeValueAsString(
                          Map.of(
                              "code",
                              command.code(),
                              "admin_id",
                              user.toString(),
                              "credential_issued",
                              true)));
                  remember("PLATFORM", actor, null, TENANT_CREATE, digest, result);
                  return new CreatedTenant(tenant, user, material.secret(), false);
                });
          } catch (DuplicateKeyException duplicate) {
            throw conflict("TENANT_CODE_CONFLICT", "租户编码已存在");
          }
        });
  }

  public CreatedAccount createUser(UserCommand command) {
    requireOwnTransaction();
    return audits.attempt(
        ManagementAudits.Action.TENANT_USER_CREATE, null, () -> createUserInternal(command));
  }

  private CreatedAccount createUserInternal(UserCommand command) {
    String normalized = AccountFields.username(command.username());
    var profile = command.profile().normalized();
    String display = profile.displayName();
    var groupRefs =
        UserGroupService.references(
            command.groups() == null ? List.of() : command.groups(), 1000, true);
    var roleRefs =
        UserGroupService.references(
            command.roles() == null ? List.of() : command.roles(), 1000, true);
    UUID actor = tenantAuthorization.actorFor("identity:user:manage");
    UUID tenant = tenantAuthorization.tenantId();
    String digest =
        digest(
            Map.of(
                "username",
                normalized,
                "profile",
                JSON.writeValueAsString(profile),
                "groups",
                JSON.writeValueAsString(
                    groupRefs.stream()
                        .sorted(java.util.Comparator.comparing(UserGroupService.Reference::id))
                        .toList()),
                "roles",
                JSON.writeValueAsString(
                    roleRefs.stream()
                        .sorted(java.util.Comparator.comparing(UserGroupService.Reference::id))
                        .toList())));
    var issued = new AtomicReference<OneTimeSecret>();
    return guarded(
        issued,
        () -> {
          try {
            return tenantAuthorization.execute(
                tenantTransactions,
                () -> {
                  tenantAuthorization.lockCatalog();
                  var row = tenantAuthorization.lockTenant(tenant);
                  if (row == null
                      || !row.status().equals("ACTIVE")
                      || row.expiresAt() != null && !clock.instant().isBefore(row.expiresAt())) {
                    throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                  }
                  var acting =
                      TenantManagementAuthorization.targetTenant() == null
                          ? users.findUser(tenant, actor)
                          : null;
                  tenantAuthorization.requireProtectedAdministrator(
                      tenant,
                      actor,
                      "identity:user:manage",
                      acting == null ? List.of() : List.of(acting));
                  recent.require(tenantAuthorization.authenticationTenant());
                  var previous =
                      prior(tenantAuthorization.actorType(), actor, tenant, USER_CREATE, digest);
                  if (previous != null) {
                    return new CreatedAccount(
                        UUID.fromString(previous.get("id").asString()), null, true);
                  }
                  if (!groupRefs.isEmpty()) tenantAuthorization.actorFor("identity:group:assign");
                  if (!roleRefs.isEmpty()) tenantAuthorization.actorFor("identity:role:assign");
                  groups.lock(
                      tenant, groupRefs.stream().map(UserGroupService.Reference::id).toList());
                  for (var ref : groupRefs) {
                    var group = groups.get(tenant, ref.id());
                    if (group == null) throw new PlatformException("NOT_FOUND", "用户组不存在或不可见", 404);
                    if (group.version() != ref.version())
                      throw conflict("GROUP_VERSION_CONFLICT", "用户组已变化，请刷新");
                    if (!group.effectiveStatus().equals("ACTIVE"))
                      throw conflict("GROUP_UNAVAILABLE", "用户组或上级已停用");
                  }
                  var roleRows =
                      groups.lockRoles(
                          tenant, roleRefs.stream().map(UserGroupService.Reference::id).toList());
                  for (var ref : roleRefs) {
                    var role =
                        roleRows.stream()
                            .filter(r -> r.id().equals(ref.id()))
                            .findFirst()
                            .orElseThrow(
                                () -> new PlatformException("NOT_FOUND", "角色不存在或不可见", 404));
                    if (role.version() != ref.version())
                      throw conflict("ROLE_VERSION_CONFLICT", "角色已变化，请刷新");
                    if (role.protectedRole() || !role.status().equals("ACTIVE"))
                      throw new PlatformException("ROLE_NOT_ASSIGNABLE", "请选择有效普通业务角色", 403);
                  }
                  var material = passwords.temporary(policies.current(tenant));
                  issued.set(material.secret());
                  UUID id = UUID.randomUUID();
                  accounts.createUser(id, tenant, normalized, display, material.hash());
                  users.updateProfile(
                      tenant,
                      id,
                      0,
                      profile.displayName(),
                      profile.realName(),
                      profile.employeeNo(),
                      profile.contactEmail(),
                      profile.phone(),
                      profile.avatarUrl(),
                      profile.description());
                  for (var ref : groupRefs) {
                    groups.membership(tenant, ref.id(), id, true);
                    groups.touch(tenant, ref.id());
                  }
                  for (var ref : roleRefs) {
                    accounts.initialMembership(tenant, id, ref.id());
                    accounts.touchRole(tenant, ref.id());
                  }
                  accounts.audit(
                      actor,
                      tenantAuthorization.actorType(),
                      tenant,
                      USER_CREATE,
                      id.toString(),
                      CallContexts.current().requestId(),
                      JSON.writeValueAsString(
                          Map.of(
                              "username",
                              normalized,
                              "credential_issued",
                              true,
                              "groups",
                              groupRefs.stream().map(UserGroupService.Reference::id).toList(),
                              "roles",
                              roleRefs.stream().map(UserGroupService.Reference::id).toList())));
                  remember(
                      tenantAuthorization.actorType(),
                      actor,
                      tenant,
                      USER_CREATE,
                      digest,
                      Map.of("id", id.toString()));
                  return new CreatedAccount(id, material.secret(), false);
                });
          } catch (DuplicateKeyException duplicate) {
            throw conflict("ACCOUNT_CONFLICT", "当前租户内账号名称或工号已存在");
          }
        });
  }

  public CreatedAccount createPlatformAdministrator(String username, String displayName) {
    requireOwnTransaction();
    return audits.attempt(
        ManagementAudits.Action.PLATFORM_ADMIN_CREATE,
        null,
        () -> createPlatformAdministratorInternal(username, displayName));
  }

  private CreatedAccount createPlatformAdministratorInternal(String username, String displayName) {
    String normalized = AccountFields.username(username);
    String display = AccountFields.displayName(displayName);
    UUID actor = platform.actorFor("platform:admin:manage");
    String digest = digest(Map.of("username", normalized, "display_name", display));
    var issued = new AtomicReference<OneTimeSecret>();
    return guarded(
        issued,
        () -> {
          try {
            return controlTransactions.execute(
                status -> {
                  users.lockCatalog();
                  platform.requireActive(actor);
                  recent.require(null);
                  var previous = prior("PLATFORM", actor, null, PLATFORM_CREATE, digest);
                  if (previous != null) {
                    return new CreatedAccount(
                        UUID.fromString(previous.get("id").asString()), null, true);
                  }
                  var material = passwords.temporary(policies.current(null));
                  issued.set(material.secret());
                  UUID id = UUID.randomUUID();
                  accounts.createPlatformUser(id, normalized, display, material.hash());
                  accounts.audit(
                      actor,
                      "PLATFORM",
                      null,
                      PLATFORM_CREATE,
                      id.toString(),
                      CallContexts.current().requestId(),
                      JSON.writeValueAsString(
                          Map.of("username", normalized, "credential_issued", true)));
                  remember(
                      "PLATFORM",
                      actor,
                      null,
                      PLATFORM_CREATE,
                      digest,
                      Map.of("id", id.toString()));
                  return new CreatedAccount(id, material.secret(), false);
                });
          } catch (DuplicateKeyException duplicate) {
            throw conflict("PLATFORM_USERNAME_CONFLICT", "平台账号名称已存在");
          }
        });
  }

  private tools.jackson.databind.JsonNode prior(
      String type, UUID actor, UUID tenant, String action, String digest) {
    var request = accounts.prior(type, actor, tenant, action, CallContexts.current().requestId());
    if (request == null) {
      return null;
    }
    if (!request.digest().equals(digest)) {
      throw conflict("REQUEST_ID_CONFLICT", "相同请求标识不能用于不同创建参数");
    }
    return JSON.readTree(request.result());
  }

  private void remember(
      String type,
      UUID actor,
      UUID tenant,
      String action,
      String digest,
      Map<String, String> result) {
    accounts.remember(
        type,
        actor,
        tenant,
        action,
        CallContexts.current().requestId(),
        digest,
        JSON.writeValueAsString(result));
  }

  private static String digest(Map<String, String> payload) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      JSON.writeValueAsString(new TreeMap<>(payload))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }

  private static <T> T guarded(AtomicReference<OneTimeSecret> issued, Supplier<T> action) {
    try {
      return action.get();
    } catch (RuntimeException | Error failure) {
      if (issued.get() != null) {
        issued.get().close();
      }
      throw failure;
    }
  }

  private static void requireOwnTransaction() {
    // 返回的一次性凭证只能属于已提交的创建结果，不能在调用者尚未提交的外层事务中交付。
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("账号创建必须通过独立事务入口执行");
    }
  }

  private static PlatformException invalid() {
    return new PlatformException("TENANT_FIELDS_INVALID", "租户参数或期限不合法", 400);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
