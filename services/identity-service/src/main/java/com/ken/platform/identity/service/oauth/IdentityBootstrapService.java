package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.bootstrap.BootstrapStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.account.AccountFields;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 独立维护进程使用的固定首次安装，不向运行服务注册 bean 或 HTTP 路由。 */
public final class IdentityBootstrapService {
  public record Command(
      String project,
      String environment,
      String publicOrigin,
      String adminUsername,
      String adminName,
      String initialPassword,
      String platformSecret,
      String tenantSecret,
      Map<String, String> publisherSecrets) {
    public Command {
      publisherSecrets = Map.copyOf(publisherSecrets);
    }

    @Override
    public String toString() {
      return "IdentityBootstrapCommand[REDACTED]";
    }
  }

  public record Result(UUID initialAdminId, boolean created, Map<String, String> clients) {
    public Result {
      clients = Map.copyOf(clients);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final BootstrapStore bootstrap;
  private final SecurityAuditRepository audits;
  private final AccountManagementMapper accounts;
  private final ClientManagementService clients;
  private final ClientStore clientStore;
  private final BuiltinPermissionCatalog catalog;
  private final SecurityPolicies policies;
  private final PasswordCredentials passwords;
  private final TransactionTemplate transactions;
  private final TransactionTemplate events;

  public IdentityBootstrapService(
      BootstrapStore bootstrap,
      SecurityAuditRepository audits,
      AccountManagementMapper accounts,
      ClientManagementService clients,
      ClientStore clientStore,
      BuiltinPermissionCatalog catalog,
      SecurityPolicies policies,
      PasswordCredentials passwords,
      PlatformTransactionManager manager) {
    this.bootstrap = bootstrap;
    this.audits = audits;
    this.accounts = accounts;
    this.clients = clients;
    this.clientStore = clientStore;
    this.catalog = catalog;
    this.policies = policies;
    this.passwords = passwords;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(60);
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  public Result initialize(Command command) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("首次安装必须拥有独立事务");
    try {
      return initializeChecked(command);
    } catch (RuntimeException failure) {
      String code = failure instanceof PlatformException known ? known.code() : "INTERNAL_ERROR";
      try {
        events.execute(
            status -> {
              audits.failure(
                  null,
                  "SERVICE",
                  null,
                  "identity.bootstrap.failed",
                  null,
                  UUID.randomUUID().toString(),
                  JSON.writeValueAsString(Map.of("error_code", code)));
              return null;
            });
      } catch (RuntimeException unavailable) {
        throw new PlatformException("SECURITY_AUDIT_UNAVAILABLE", "安全审计暂不可用", 503);
      }
      throw failure;
    }
  }

  private Result initializeChecked(Command command) {
    validate(command);
    requireNamespace(command, bootstrap.state());
    catalog.install();
    return transactions.execute(
        transaction -> {
          bootstrap.lockCatalog();
          var state = bootstrap.state();
          UUID admin;
          boolean created = state == null;
          if (created) {
            if (bootstrap.platformUserCount() != 0) throw conflict();
            admin = UUID.randomUUID();
            accounts.createPlatformUser(
                admin,
                AccountFields.username(command.adminUsername()),
                AccountFields.displayName(command.adminName()),
                passwords.encode(command.initialPassword(), policies.current(null)));
            bootstrap.remember(
                command.project(), command.environment(), command.publicOrigin(), admin);
            accounts.audit(
                null,
                "SERVICE",
                null,
                "identity.bootstrap.admin.created",
                admin.toString(),
                UUID.randomUUID().toString(),
                "{\"must_change_password\":true}");
          } else {
            requireNamespace(command, state);
            admin = state.adminId();
            // 不检查初始密码是否仍匹配；已完成首次改密的账号不能被初始化重置。
            if (accounts.lockPlatformUser(admin) == null) throw conflict();
          }
          var audience = bootstrap.identityAudience();
          if (audience.isEmpty()) bootstrap.createIdentityAudience();
          else if (audience.size() != 1
              || !"identity-service".equals(audience.getFirst().audience())
              || !"ACTIVE".equals(audience.getFirst().status())) throw conflict();
          Map<String, String> ids = new TreeMap<>();
          initializeClient(
              command,
              "PLATFORM_BFF",
              "identity-admin-platform",
              PrincipalType.PLATFORM,
              "platform",
              command.platformSecret(),
              null,
              ids);
          initializeClient(
              command,
              "TENANT_BFF",
              "identity-admin-tenant",
              PrincipalType.USER,
              "tenant",
              command.tenantSecret(),
              null,
              ids);
          command.publisherSecrets().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .forEach(
                  entry ->
                      initializeClient(
                          command,
                          "PUBLISHER:" + entry.getKey(),
                          publisherClientId(entry.getKey()),
                          PrincipalType.SERVICE,
                          null,
                          entry.getValue(),
                          entry.getKey(),
                          ids));
          return new Result(admin, created, ids);
        });
  }

  private void initializeClient(
      Command command,
      String purpose,
      String id,
      PrincipalType type,
      String namespace,
      String secret,
      String service,
      Map<String, String> ids) {
    Set<UUID> tenants = Set.of();
    if (purpose.equals("TENANT_BFF")) {
      var existing = clientStore.byClientId(id);
      if (existing != null) tenants = existing.policy().allowedTenantIds();
    }
    Set<String> scopes;
    if (service == null) {
      var values =
          BuiltinPermissionCatalog.entries().stream()
              .filter(
                  row ->
                      row.principalType()
                          .equals(type == PrincipalType.PLATFORM ? "PLATFORM" : "TENANT"))
              .map(row -> row.oauthScope())
              .collect(Collectors.toSet());
      values.addAll(Set.of("openid", "profile"));
      scopes = Set.copyOf(values);
    } else scopes = Set.of("catalog.publish:" + service);
    var definition =
        new ClientPolicyRules.Definition(
            id,
            service == null
                ? (type == PrincipalType.PLATFORM ? "平台身份管理" : "租户身份管理")
                : "权限目录发布：" + service,
            type,
            null,
            tenants,
            Set.of("identity-service"),
            scopes,
            namespace == null
                ? Set.of()
                : Set.of(command.publicOrigin() + "/bff/identity/" + namespace + "/callback"),
            namespace == null
                ? Set.of()
                : Set.of(command.publicOrigin() + "/bff/identity/" + namespace + "/logged-out"),
            false,
            namespace != null,
            Status.ACTIVE);
    clients.initialize(
        purpose, definition, secret, passwords.encode(secret, policies.current(null)));
    ids.put(purpose, id);
  }

  public static String publisherClientId(String service) {
    try {
      return "catalog-publisher-"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(service.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 24);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }

  private static void validate(Command value) {
    if (value == null
        || value.project() == null
        || value.project().length() > 64
        || !value.project().matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        || !Set.of("local", "test", "prod")
            .contains(value.environment() == null ? "" : value.environment())
        || value.publisherSecrets().size() > 100
        || value.publisherSecrets().keySet().stream()
            .anyMatch(
                service ->
                    !service.matches("[a-z][a-z0-9-]{0,127}")
                        || service.equals("identity-service"))) throw invalid();
    AccountFields.username(value.adminUsername());
    AccountFields.displayName(value.adminName());
    try {
      URI uri = URI.create(value.publicOrigin());
      boolean local =
          value.environment().equals("local")
              && "http".equals(uri.getScheme())
              && Set.of("localhost", "127.0.0.1", "[::1]")
                  .contains(uri.getHost() == null ? "" : uri.getHost());
      if (uri.getHost() == null
          || !(local || "https".equals(uri.getScheme()))
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getRawPath().isEmpty()
          || uri.getPort() > 65535
          || uri.getPort() == 0) throw invalid();
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    var secrets = new HashMap<>(value.publisherSecrets());
    secrets.put("PLATFORM_BFF", value.platformSecret());
    secrets.put("TENANT_BFF", value.tenantSecret());
    if (secrets.values().stream()
        .anyMatch(secret -> secret == null || !secret.matches("[A-Za-z0-9_-]{32,64}")))
      throw invalid();
  }

  private static PlatformException invalid() {
    return new PlatformException("IDENTITY_BOOTSTRAP_INVALID", "初始化绑定、提供方服务或秘密格式不合法", 400);
  }

  private static void requireNamespace(Command command, BootstrapStore.State state) {
    if (state != null
        && (!command.project().equals(state.project())
            || !command.environment().equals(state.environment())
            || !command.publicOrigin().equals(state.origin()))) throw conflict();
  }

  private static PlatformException conflict() {
    return new PlatformException("IDENTITY_BOOTSTRAP_CONFLICT", "已有初始化资源与登记不一致，停止且不覆盖", 409);
  }
}
