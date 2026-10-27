package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.bootstrap.BootstrapStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 离线维护入口：只登记服务技术身份和应用客户端，不创建业务账号或角色。 */
public final class ServiceProvisioning {
  public record Service(
      String name,
      String publisherSecret,
      String resourceSecret,
      String gatewaySecret,
      String swaggerRedirect) {
    @Override
    public String toString() {
      return "Service[REDACTED]";
    }
  }

  private final JdbcTemplate jdbc;
  private final ClientStore store;
  private final ClientManagementService clients;
  private final TransactionTemplate transactions;

  public ServiceProvisioning(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ClientStore store,
      ClientManagementService clients) {
    this.jdbc = jdbc;
    this.store = store;
    this.clients = clients;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(60);
  }

  public void apply(
      String project,
      String environment,
      String origin,
      String database,
      String phase,
      List<Service> services,
      String webSecret,
      boolean demo) {
    if (!Set.of("technical", "applications").contains(phase)
        || services.size() > 100
        || services.stream().map(Service::name).distinct().count() != services.size())
      throw invalid();
    for (var service : services) {
      if (service.name() == null
          || !service.name().matches("[a-z][a-z0-9-]{0,29}")
          || Set.of("identity-service", "api-gateway").contains(service.name())) throw invalid();
    }
    transactions.executeWithoutResult(
        status -> {
          var bootstrap = new BootstrapStore(jdbc);
          bootstrap.lockCatalog();
          var state = bootstrap.state();
          if (state == null
              || !state.project().equals(project)
              || !state.environment().equals(environment)
              || !state.origin().equals(origin)
              || !database.equals(jdbc.queryForObject("SELECT current_database()", String.class)))
            throw invalid();
          Set<java.util.UUID> allowed = Set.of();
          if (demo && phase.equals("applications")) {
            if (!environment.equals("local")
                || jdbc.queryForObject(
                        "SELECT count(*) FROM sys_local_development_bootstrap WHERE id=1 AND project_code=?",
                        Long.class,
                        project)
                    != 1) throw invalid();
            allowed =
                Set.copyOf(
                    jdbc.queryForList(
                        "SELECT id FROM sys_tenant WHERE code IN ('alpha','beta')",
                        java.util.UUID.class));
            if (allowed.size() != 2) throw invalid();
          }
          var audiences = new HashSet<>(Set.of("identity-service"));
          var scopes = new HashSet<>(Set.of("profile"));
          for (var service : services) {
            var bindings = store.findAudienceBindings(service.name(), service.name());
            if (bindings.isEmpty() && phase.equals("technical"))
              store.registerAudience(service.name(), service.name());
            else if (bindings.size() != 1
                || !bindings.getFirst().service().equals(service.name())
                || !bindings.getFirst().audience().equals(service.name())
                || !bindings.getFirst().status().equals("ACTIVE")) throw invalid();
            if (phase.equals("technical")) {
              machine(
                  IdentityBootstrapService.publisherClientId(service.name()),
                  "catalog.publish:" + service.name(),
                  service.publisherSecret());
              machine(
                  service.name() + "-resource",
                  "internal.introspect:" + service.name(),
                  service.resourceSecret());
              machine(
                  service.name() + "-gateway",
                  "internal.introspect:" + service.name(),
                  service.gatewaySecret());
            } else {
              var ownScopes =
                  new HashSet<>(store.businessScopes(PrincipalType.USER, Set.of(service.name())));
              ownScopes.add("profile");
              audiences.add(service.name());
              scopes.addAll(ownScopes);
              if (environment.equals("local")) {
                // 精确 loopback 回调由标准客户端规则校验；生产不建立 Swagger 客户端。
                clients.provision(
                    new ClientPolicyRules.Definition(
                        service.name() + "-swagger",
                        service.name() + " Swagger",
                        PrincipalType.USER,
                        null,
                        allowed,
                        Set.of(service.name()),
                        Set.copyOf(ownScopes),
                        Set.of(service.swaggerRedirect()),
                        Set.of(),
                        true,
                        false,
                        Status.ACTIVE),
                    null);
              }
            }
          }
          if (phase.equals("applications") && !services.isEmpty()) {
            clients.provision(
                new ClientPolicyRules.Definition(
                    "web-bff",
                    "业务 Web",
                    PrincipalType.USER,
                    null,
                    Set.of(),
                    Set.copyOf(audiences),
                    Set.copyOf(scopes),
                    Set.of(origin + "/bff/web/callback"),
                    Set.of(origin + "/bff/web/logged-out"),
                    false,
                    true,
                    Status.ACTIVE),
                webSecret);
          }
        });
  }

  private void machine(String id, String scope, String secret) {
    clients.provision(
        new ClientPolicyRules.Definition(
            id,
            id,
            PrincipalType.SERVICE,
            null,
            Set.of(),
            Set.of("identity-service"),
            Set.of(scope),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE),
        secret);
  }

  private static PlatformException invalid() {
    return new PlatformException("SERVICE_PROVISIONING_INVALID", "服务接入输入或环境与现有登记不符", 409);
  }
}
