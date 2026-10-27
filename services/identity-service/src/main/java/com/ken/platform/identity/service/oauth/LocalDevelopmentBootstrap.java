package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.bootstrap.BootstrapStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 固定的首次开发数据安装；不作为 Web Bean，不模拟管理员主体或提供通用导入能力。 */
public final class LocalDevelopmentBootstrap {
  public record Service(String name, String resourceSecret, String gatewaySecret) {
    @Override
    public String toString() {
      return "Service[REDACTED]";
    }
  }

  public record Input(
      String project,
      String environment,
      String origin,
      String database,
      String mode,
      String definition,
      List<Service> services,
      Map<String, String> passwords,
      String webSecret) {
    @Override
    public String toString() {
      return "Input[REDACTED]";
    }
  }

  private final JdbcTemplate jdbc;
  private final BootstrapStore bootstrap;
  private final ClientStore store;
  private final ClientManagementService clients;
  private final AccountManagementMapper accounts;
  private final PasswordCredentials passwords;
  private final SecurityPolicies policies;
  private final TransactionTemplate transactions;

  public LocalDevelopmentBootstrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ClientStore store,
      ClientManagementService clients,
      AccountManagementMapper accounts,
      PasswordCredentials passwords,
      SecurityPolicies policies) {
    this.jdbc = jdbc;
    this.bootstrap = new BootstrapStore(jdbc);
    this.store = store;
    this.clients = clients;
    this.accounts = accounts;
    this.passwords = passwords;
    this.policies = policies;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(60);
  }

  public static void validate(Input input) {
    if (input == null
        || !"local".equals(input.environment())
        || !"self_hosted".equals(input.mode())
        || input.project() == null
        || !input.project().matches("[a-z][a-z0-9-]{0,29}")
        || !input.database().equals(input.project().replace('-', '_') + "_local_identity")
        || input.services() == null
        || input.services().size() != 2
        || input.definition() == null
        || !input.definition().matches("[a-f0-9]{64}")
        || input.passwords() == null
        || input.passwords().size() != 6) throw invalid();
    var names = new HashSet<String>();
    for (var service : input.services()) {
      if (service.name() == null
          || !service.name().matches("[a-z][a-z0-9-]{0,29}")
          || Set.of("identity-service", "api-gateway").contains(service.name())
          || !names.add(service.name())) throw invalid();
      requireSecret(service.resourceSecret());
      requireSecret(service.gatewaySecret());
    }
    requireSecret(input.webSecret());
    for (String tenant : List.of("alpha", "beta")) {
      for (String user : List.of("admin", "writer", "denied")) {
        if (!PasswordCredentials.bounded(input.passwords().get(tenant + ":" + user)))
          throw invalid();
      }
    }
  }

  public boolean initialize(Input input) {
    validate(input);
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              bootstrap.lockCatalog();
              var state = bootstrap.state();
              if (state == null
                  || !state.project().equals(input.project())
                  || !state.environment().equals("local")
                  || !state.origin().equals(input.origin())
                  || !input
                      .database()
                      .equals(jdbc.queryForObject("SELECT current_database()", String.class)))
                throw invalid();
              var prior =
                  jdbc.queryForList(
                      "SELECT project_code,definition_digest FROM sys_local_development_bootstrap WHERE id=1");
              if (!prior.isEmpty()) {
                if (!prior.getFirst().get("project_code").equals(input.project())
                    || !prior.getFirst().get("definition_digest").equals(input.definition()))
                  throw conflict();
                return false; // 不还原管理员已修改的角色、密码、客户端。
              }
              var scopes = new HashSet<>(Set.of("profile"));
              var audiences = new HashSet<>(Set.of("identity-service"));
              for (var service : input.services()) {
                if (!store.findAudienceBindings(service.name(), service.name()).isEmpty())
                  throw conflict();
                store.registerAudience(service.name(), service.name());
                audiences.add(service.name());
                scopes.add(service.name() + ".items.read");
                scopes.add(service.name() + ".items.write");
                Long count =
                    jdbc.queryForObject(
                        """
            SELECT count(*) FROM sys_permission WHERE owner_service=? AND status='ACTIVE'
            AND principal_type='TENANT' AND ((code=? AND oauth_scope=? AND 'TENANT'=ANY(supported_data_scopes))
            OR (code=? AND oauth_scope=? AND cardinality(supported_data_scopes)=0))
            """,
                        Long.class,
                        service.name(),
                        service.name() + "-item:read",
                        service.name() + ".items.read",
                        service.name() + "-item:create",
                        service.name() + ".items.write");
                if (count == null || count != 2) throw invalid();
                for (String kind : List.of("resource", "gateway")) {
                  String secret =
                      kind.equals("resource") ? service.resourceSecret() : service.gatewaySecret();
                  initializeClient(
                      service.name() + "-" + kind,
                      PrincipalType.SERVICE,
                      Set.of(),
                      Set.of("identity-service"),
                      Set.of("internal.introspect:" + service.name()),
                      Set.of(),
                      Set.of(),
                      secret);
                }
              }
              var tenants = new HashSet<UUID>();
              for (String code : List.of("alpha", "beta")) {
                if (jdbc.queryForObject(
                        "SELECT count(*) FROM sys_tenant WHERE code=?", Long.class, code)
                    != 0) throw conflict();
                UUID tenant = UUID.randomUUID();
                accounts.createTenant(tenant, code, "开发租户 " + code, null);
                tenants.add(tenant);
                clients.enrollBootstrapTenant(tenant);
                jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id',?,true)", String.class, tenant.toString());
                UUID adminRole = UUID.randomUUID();
                UUID writerRole = UUID.randomUUID();
                accounts.createProtectedRole(adminRole, tenant);
                var core = BuiltinPermissionCatalog.PROTECTED_TENANT_PERMISSIONS;
                if (accounts.grantCorePermissions(adminRole, tenant, core) != core.size())
                  throw invalid();
                jdbc.update(
                    "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'demo-writer','开发演示角色')",
                    writerRole,
                    tenant);
                for (var service : input.services()) {
                  jdbc.update(
                      "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT'),(?,?,?,NULL)",
                      tenant,
                      writerRole,
                      service.name() + "-item:read",
                      tenant,
                      writerRole,
                      service.name() + "-item:create");
                }
                for (String user : List.of("admin", "writer", "denied")) {
                  UUID id = UUID.randomUUID();
                  accounts.createUser(
                      id,
                      tenant,
                      user,
                      "开发 " + user,
                      passwords.encode(
                          input.passwords().get(code + ":" + user), policies.current(tenant)));
                  if (!user.equals("denied"))
                    accounts.initialMembership(
                        tenant, id, user.equals("admin") ? adminRole : writerRole);
                }
              }
              jdbc.queryForObject("SELECT set_config('app.tenant_id','',true)", String.class);
              initializeClient(
                  "web-bff",
                  PrincipalType.USER,
                  tenants,
                  audiences,
                  scopes,
                  Set.of(input.origin() + "/bff/web/callback"),
                  Set.of(input.origin() + "/bff/web/logged-out"),
                  input.webSecret());
              jdbc.update(
                  "INSERT INTO sys_local_development_bootstrap(id,project_code,definition_digest) VALUES(1,?,?)",
                  input.project(),
                  input.definition());
              accounts.audit(
                  null,
                  "SERVICE",
                  null,
                  "identity.local-development.created",
                  input.project(),
                  UUID.randomUUID().toString(),
                  "{}");
              return true;
            }));
  }

  private void initializeClient(
      String id,
      PrincipalType type,
      Set<UUID> tenants,
      Set<String> audiences,
      Set<String> scopes,
      Set<String> redirects,
      Set<String> logouts,
      String secret) {
    clients.initialize(
        "LOCAL:" + id,
        new ClientPolicyRules.Definition(
            id,
            id,
            type,
            null,
            tenants,
            audiences,
            scopes,
            redirects,
            logouts,
            false,
            true,
            Status.ACTIVE),
        secret,
        passwords.encoder().encode(secret));
  }

  private static void requireSecret(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{32,64}")) throw invalid();
  }

  private static PlatformException invalid() {
    return new PlatformException("LOCAL_BOOTSTRAP_INPUT_INVALID", "仅允许本项目自建 local 身份库与固定开发清单", 400);
  }

  private static PlatformException conflict() {
    return new PlatformException("LOCAL_BOOTSTRAP_CONFLICT", "初始化定义或已有身份冲突；后续授权请使用管理页", 409);
  }
}
