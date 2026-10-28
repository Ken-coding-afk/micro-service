package com.ken.platform.identity.maintenance;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.oauth.ClientManagementService;
import com.ken.platform.identity.service.oauth.ClientPolicyRules;
import com.ken.platform.identity.service.oauth.IdentityBootstrapService;
import com.ken.platform.identity.service.permission.BuiltinPermissionCatalog;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.RecentAuthentication;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** java -jar identity-service.jar bootstrap；不启动 Web、Nacos、Redis 或调度器。 */
public final class IdentityBootstrapMain {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private IdentityBootstrapMain() {}

  public static int run(Map<String, String> env, String[] args) {
    try (var ignored =
        com.ken.platform.foundation.http.CallContexts.bind(
            new com.ken.platform.foundation.http.CallContext(
                UUID.randomUUID().toString(), null, null, null, null, null))) {
      if (args.length != 1
          || !Set.of(
                  "bootstrap",
                  "bootstrap-monitoring",
                  "bootstrap-local",
                  "bootstrap-services-technical",
                  "bootstrap-services-applications")
              .contains(args[0])) throw invalid();
      boolean local = args[0].equals("bootstrap-local");
      boolean services = args[0].startsWith("bootstrap-services-");
      String environment = required(env, "APP_ENV");
      new ClientPolicyRules(environment);
      if (local && !environment.equals("local")) throw invalid();
      var command = local || services ? null : command(env);
      String database = identifier(required(env, "DB_NAME"));
      String user = identifier(required(env, "DB_USER"));
      String host = required(env, "PG_HOST");
      if (!host.matches("[A-Za-z0-9.:-]{1,253}")) throw invalid();
      int port = Integer.parseInt(required(env, "PG_PORT"));
      if (port < 1 || port > 65535) throw invalid();
      if (host.contains(":")) host = "[" + host + "]";
      var config = new HikariConfig();
      config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
      config.setUsername(user);
      config.setPassword(secret(required(env, "DB_PASSWORD_SECRET_FILE"), 4096));
      config.setMaximumPoolSize(2);
      config.setConnectionTimeout(5000);
      config.setInitializationFailTimeout(5000);
      try (var source = new HikariDataSource(config)) {
        var jdbc = new JdbcTemplate(source);
        var manager = new JdbcTransactionManager(source);
        var configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        configuration.addMapper(AccountManagementMapper.class);
        configuration.addMapper(UserManagementMapper.class);
        configuration.addMapper(SecurityPolicyMapper.class);
        configuration.addMapper(PermissionCatalogMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mappers = new SqlSessionTemplate(factory.getObject());
        var accounts = mappers.getMapper(AccountManagementMapper.class);
        var security = mappers.getMapper(SecurityPolicyMapper.class);
        var policies = new SecurityPolicies(security);
        var passwords = new PasswordCredentials(security);
        var store = new ClientStore(jdbc);
        var clock = Clock.systemUTC();
        var clients =
            new ClientManagementService(
                manager,
                store,
                new ClientPolicyRules(environment),
                mappers.getMapper(UserManagementMapper.class),
                accounts,
                new PlatformManagementAuthorization(accounts),
                new ManagementAudits(new SecurityAuditRepository(jdbc), manager),
                passwords,
                clock,
                new RecentAuthentication(policies, clock));
        var catalog =
            new BuiltinPermissionCatalog(mappers.getMapper(PermissionCatalogMapper.class), manager);
        if (services) {
          var input =
              JSON.readTree(secret(required(env, "SERVICE_PROVISIONING_SECRET_FILE"), 65536));
          var entries =
              new java.util.ArrayList<
                  com.ken.platform.identity.service.oauth.ServiceProvisioning.Service>();
          if (!input.isObject() || !input.path("services").isArray()) throw invalid();
          for (var entry : input.get("services")) {
            entries.add(
                new com.ken.platform.identity.service.oauth.ServiceProvisioning.Service(
                    text(entry, "name"),
                    secret(text(entry, "publisher_secret_file"), 128),
                    secret(text(entry, "resource_secret_file"), 128),
                    secret(text(entry, "gateway_secret_file"), 128),
                    environment.equals("local") ? text(entry, "swagger_redirect") : ""));
          }
          new com.ken.platform.identity.service.oauth.ServiceProvisioning(
                  jdbc, manager, store, clients)
              .apply(
                  required(env, "PROJECT_CODE"),
                  environment,
                  required(env, "PUBLIC_ORIGIN"),
                  database,
                  args[0].substring("bootstrap-services-".length()),
                  entries,
                  secret(text(input, "web_secret_file"), 128),
                  input.path("demo").asBoolean(false));
          System.out.println("{\"status\":\"VERIFIED\",\"policy\":\"EXISTING_PRESERVED\"}");
          return 0;
        }
        if (local) {
          boolean created =
              new com.ken.platform.identity.service.oauth.LocalDevelopmentBootstrap(
                      jdbc, manager, store, clients, accounts, passwords, policies)
                  .initialize(localCommand(env));
          System.out.println(
              JSON.writeValueAsString(Map.of("status", created ? "CREATED" : "VERIFIED")));
          return 0;
        }
        var result =
            new IdentityBootstrapService(
                    new com.ken.platform.identity.dal.mapper.bootstrap.BootstrapStore(jdbc),
                    new SecurityAuditRepository(jdbc),
                    accounts,
                    clients,
                    store,
                    catalog,
                    policies,
                    passwords,
                    manager)
                .initialize(command, args[0].equals("bootstrap-monitoring"));
        System.out.println(
            JSON.writeValueAsString(
                Map.of(
                    "status",
                    result.created() ? "CREATED" : "VERIFIED",
                    "initial_admin_id",
                    result.initialAdminId(),
                    "clients",
                    result.clients())));
      }
      return 0;
    } catch (Exception failure) {
      String code =
          failure instanceof PlatformException known ? known.code() : "IDENTITY_BOOTSTRAP_FAILED";
      System.err.println(JSON.writeValueAsString(Map.of("status", "FAILED", "code", code)));
      return 1;
    }
  }

  private static com.ken.platform.identity.service.oauth.LocalDevelopmentBootstrap.Input
      localCommand(Map<String, String> env) throws Exception {
    var root = JSON.readTree(secret(required(env, "LOCAL_BOOTSTRAP_SECRET_FILE"), 65536));
    if (!root.isObject()
        || !root.get("services").isArray()
        || root.get("services").isEmpty()
        || root.get("services").size() > 100) throw invalid();
    var services =
        new java.util.ArrayList<
            com.ken.platform.identity.service.oauth.LocalDevelopmentBootstrap.Service>();
    for (var service : root.get("services")) {
      services.add(
          new com.ken.platform.identity.service.oauth.LocalDevelopmentBootstrap.Service(
              text(service, "name"),
              secret(text(service, "resource_secret_file"), 128),
              secret(text(service, "gateway_secret_file"), 128)));
    }
    Map<String, String> passwords = new java.util.TreeMap<>();
    for (String tenant : java.util.List.of("alpha", "beta")) {
      for (String user : java.util.List.of("admin", "writer", "denied")) {
        passwords.put(
            tenant + ":" + user,
            secret(text(root.get("password_files"), tenant + ":" + user), 512));
      }
    }
    // 摘要使用规范化结构和秘密文件引用，不保存秘密值。
    String canonical = JSON.writeValueAsString(JSON.convertValue(root, java.util.TreeMap.class));
    String digest =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    return new com.ken.platform.identity.service.oauth.LocalDevelopmentBootstrap.Input(
        required(env, "PROJECT_CODE"),
        required(env, "APP_ENV"),
        required(env, "PUBLIC_ORIGIN"),
        required(env, "DB_NAME"),
        required(env, "LOCAL_PG_MODE"),
        digest,
        services,
        passwords,
        secret(text(root, "web_secret_file"), 128));
  }

  private static IdentityBootstrapService.Command command(Map<String, String> env)
      throws Exception {
    String path = required(env, "IDENTITY_BOOTSTRAP_SECRET_FILE");
    var root = JSON.readTree(secret(path, 65536));
    Set<String> fields =
        Set.of(
            "admin_username",
            "admin_display_name",
            "initial_password_file",
            "platform_bff_secret_file",
            "tenant_bff_secret_file",
            "publisher_secret_files");
    if (!root.isObject()
        || root.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey())))
      throw invalid();
    var publishers = root.get("publisher_secret_files");
    if (publishers == null || !publishers.isObject() || publishers.size() > 100) throw invalid();
    Map<String, String> secrets = new HashMap<>();
    for (var entry : publishers.properties()) {
      if (!entry.getValue().isString()) throw invalid();
      secrets.put(entry.getKey(), secret(entry.getValue().asString(), 128));
    }
    return new IdentityBootstrapService.Command(
        required(env, "PROJECT_CODE"),
        required(env, "APP_ENV"),
        required(env, "PUBLIC_ORIGIN"),
        text(root, "admin_username"),
        text(root, "admin_display_name"),
        secret(text(root, "initial_password_file"), 512),
        secret(text(root, "platform_bff_secret_file"), 128),
        secret(text(root, "tenant_bff_secret_file"), 128),
        secrets);
  }

  private static String text(JsonNode node, String key) {
    var value = node.get(key);
    if (value == null || !value.isString()) throw invalid();
    return value.asString();
  }

  private static String required(Map<String, String> env, String key) {
    var value = env.get(key);
    if (value == null || value.isBlank()) throw invalid();
    return value;
  }

  private static String identifier(String value) {
    if (!value.matches("[a-z_][a-z0-9_]{0,62}")) throw invalid();
    return value;
  }

  private static String secret(String value, int maxBytes) throws Exception {
    Path path = Path.of(value);
    if (!path.isAbsolute() || !Files.isRegularFile(path)) throw invalid();
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(maxBytes + 1);
    }
    if (bytes.length > maxBytes) throw invalid();
    String content =
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
    if (content.isEmpty()) throw invalid();
    return content;
  }

  private static PlatformException invalid() {
    return new PlatformException("IDENTITY_BOOTSTRAP_INPUT_INVALID", "初始化输入不合法", 400);
  }
}
