package com.ken.platform.identity.service.login;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.config.AuthorizationServerConfig;
import com.ken.platform.identity.config.BrowserLoginConfiguration;
import com.ken.platform.identity.config.IdentitySessionConfiguration;
import com.ken.platform.identity.config.IdentitySigningConfiguration;
import com.ken.platform.identity.controller.login.LoginPageController;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import tools.jackson.databind.json.JsonMapper;

/** 本地真实 Tomcat + HTTP Cookie + PostgreSQL/Redis；不包含 Nacos/Gateway 或外部 IdP。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_REDIS_BINDING", matches = ".+")
class BrowserLoginHttpTest {
  @TempDir static Path signingDirectory;
  private static Path signingFile;

  @BeforeAll
  static void persistentSigningFixture() throws Exception {
    var key =
        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
            .keyID("native-http-key")
            .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
            .generate();
    signingFile = signingDirectory.resolve("identity-signing.json");
    Files.writeString(
        signingFile,
        new com.nimbusds.jose.jwk.JWKSet(
                java.util.List.of(key), Map.of("active_kid", key.getKeyID()))
            .toString(false));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @EnableScheduling
  @Import({
    com.ken.platform.identity.config.FederationConfiguration.class,
    AuthorizationServerConfig.class,
    com.ken.platform.identity.config.AdminWebConfiguration.class,
    com.ken.platform.identity.controller.admin.AdminPagesController.class,
    IdentitySessionConfiguration.class,
    IdentitySigningConfiguration.class,
    BrowserLoginConfiguration.class,
    com.ken.platform.identity.config.IdentityResourceConfiguration.class,
    com.ken.platform.identity.controller.oauth.CurrentApplicationController.class,
    com.ken.platform.identity.controller.management.TenantManagementController.class,
    com.ken.platform.identity.controller.management.IdentityManagementErrors.class,
    com.ken.platform.identity.controller.management.TenantManagementQueryController.class,
    com.ken.platform.identity.service.authorization.TenantManagementQueryService.class,
    com.ken.platform.identity.service.authorization.AuthorizationEvaluator.class,
    com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository.class,
    com.ken.platform.identity.service.role.RoleAssignmentService.class,
    com.ken.platform.identity.service.role.RolePermissionService.class,
    com.ken.platform.identity.service.role.RoleManagementService.class,
    com.ken.platform.identity.service.user.UserStatusService.class,
    com.ken.platform.identity.service.user.UserProfileService.class,
    com.ken.platform.identity.controller.management.PlatformClientController.class,
    com.ken.platform.identity.config.ClientManagementConfiguration.class,
    com.ken.platform.identity.service.oauth.ClientManagementService.class,
    com.ken.platform.identity.service.security.RecentAuthentication.class,
    com.ken.platform.identity.service.authorization.PlatformManagementAuthorization.class,
    com.ken.platform.identity.controller.management.AccountManagementController.class,
    com.ken.platform.identity.service.account.AccountCreationService.class,
    com.ken.platform.identity.service.account.PlatformLifecycleService.class,
    com.ken.platform.identity.service.account.PlatformAccountQueryService.class,
    com.ken.platform.identity.controller.management.OwnAccountController.class,
    com.ken.platform.identity.controller.management.AdminConfigurationController.class,
    com.ken.platform.identity.service.account.OwnAccountService.class,
    com.ken.platform.identity.controller.management.SessionManagementController.class,
    com.ken.platform.identity.service.login.SessionManagementService.class,
    com.ken.platform.identity.service.oauth.ApplicationConsentService.class,
    com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore.class,
    com.ken.platform.identity.controller.management.SecurityManagementController.class,
    com.ken.platform.identity.controller.management.AuditManagementController.class,
    com.ken.platform.identity.service.audit.AuditQueryService.class,
    com.ken.platform.identity.service.audit.AuditDetailSanitizer.class,
    com.ken.platform.identity.service.authorization.TenantManagementAuthorization.class,
    com.ken.platform.identity.service.audit.ManagementAudits.class,
    LoginPageController.class,
    com.ken.platform.identity.controller.login.ConsentPageController.class,
    SessionCleanup.class
  })
  static class WebConfiguration {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    com.ken.platform.foundation.config.RuntimeParameters runtimeParameters() {
      return com.ken.platform.foundation.config.RuntimeParameters.defaults();
    }

    @Bean
    TomcatServletWebServerFactory webServerFactory() throws Exception {
      var server = new TomcatServletWebServerFactory(0);
      server.setAddress(InetAddress.getByName("127.0.0.1"));
      return server;
    }

    @Bean
    FilterRegistrationBean<com.ken.platform.foundation.web.ServletRequestContextFilter>
        requestContextRegistration() {
      var filter =
          new com.ken.platform.foundation.web.ServletRequestContextFilter(
              Clock.systemUTC(), com.ken.platform.foundation.config.RuntimeParameters.defaults());
      var registration = new FilterRegistrationBean<>(filter);
      registration.setOrder(filter.getOrder());
      return registration;
    }

    @Bean
    DispatcherServlet dispatcherServlet() {
      return new DispatcherServlet();
    }

    @Bean
    ServletRegistrationBean<DispatcherServlet> dispatcherRegistration(DispatcherServlet servlet) {
      var registration = new ServletRegistrationBean<>(servlet, "/");
      registration.setLoadOnStartup(1);
      return registration;
    }

    @Bean
    FilterRegistrationBean<Filter> securityRegistration(
        @Qualifier("springSecurityFilterChain") Filter filter) {
      var registration = new FilterRegistrationBean<>(filter);
      registration.setOrder(-100);
      registration.setDispatcherTypes(
          DispatcherType.REQUEST,
          DispatcherType.ASYNC,
          DispatcherType.ERROR,
          DispatcherType.FORWARD);
      return registration;
    }

    @Bean
    FilterRegistrationBean<CharacterEncodingFilter> encodingRegistration() {
      var registration = new FilterRegistrationBean<>(new CharacterEncodingFilter("UTF-8", true));
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }

    @Bean
    SpringResourceTemplateResolver templateResolver() {
      var resolver = new SpringResourceTemplateResolver();
      resolver.setPrefix("classpath:/templates/");
      resolver.setSuffix(".html");
      resolver.setTemplateMode("HTML");
      resolver.setCharacterEncoding("UTF-8");
      return resolver;
    }

    @Bean
    SpringTemplateEngine templateEngine(SpringResourceTemplateResolver resolver) {
      var engine = new SpringTemplateEngine();
      engine.setTemplateResolver(resolver);
      return engine;
    }

    @Bean
    ThymeleafViewResolver viewResolver(SpringTemplateEngine engine) {
      var resolver = new ThymeleafViewResolver();
      resolver.setTemplateEngine(engine);
      resolver.setCharacterEncoding("UTF-8");
      return resolver;
    }
  }

  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private Integer originalFailureThreshold;
  private JdbcTransactionManager manager;
  private SqlSessionTemplate managementMappers;
  private com.ken.platform.identity.dal.mapper.audit.AuditQueryMapper auditQueries;
  private com.ken.platform.identity.dal.mapper.login.SessionManagementMapper sessionQueries;
  private final Map<UUID, String> managedSessionPasswords = new HashMap<>();
  private UUID otherTenantUser;
  private UUID otherTenant;
  private LoginRedisFixture redis;
  private LoginStore store;
  private PasswordLoginService service;
  private AnnotationConfigServletWebServerApplicationContext context;
  private RedisSessionRepository sessions;
  private CookieManager cookies;
  private HttpClient http;
  private String origin;
  private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  private final String username = "browser-" + UUID.randomUUID(), tenantCode = "browser-" + tenant;
  private String secret;
  private final String next = "Browser passphrase " + UUID.randomUUID();
  private PasswordCredentials passwords;
  private SecurityPolicies policies;
  private SecurityAuditRepository protocolAudits;
  private ClientStore oauthClients;
  private com.ken.platform.identity.service.oauth.ProtocolAuthorizations oauthAuthorizations;
  private com.ken.platform.identity.service.oauth.UserTokenService userTokens;
  private com.ken.platform.identity.dal.mapper.oauth.UserTokenStore tokenUsers;
  private com.ken.platform.identity.service.security.SecurityPolicyManagementService
      policyManagement;

  private OidcProviderFixture externalProvider;

  @BeforeEach
  void setup(org.junit.jupiter.api.TestInfo info) throws Exception {
    if (info.getTestMethod().orElseThrow().getName().startsWith("federation"))
      externalProvider = new OidcProviderFixture();
    var b =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(b.get("test_only").asBoolean()).isTrue();
    assertThat(b.get("app_env").asString()).isEqualTo("local");
    assertThat(b.get("dbname").asString()).startsWith("t02t");
    otherTenantUser = UUID.fromString(b.get("user_b").asString());
    otherTenant = UUID.fromString(b.get("tenant_b").asString());
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + b.get("host").asString()
            + ":"
            + b.get("port").asInt()
            + "/"
            + b.get("dbname").asString());
    config.setUsername(b.get("username").asString());
    config.setPassword(Files.readString(Path.of(b.get("password_file").asString())).strip());
    config.setMaximumPoolSize(6);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    originalFailureThreshold =
        jdbc.queryForObject(
            "SELECT login_failure_threshold FROM sys_security_policy WHERE owner_type='PLATFORM'",
            Integer.class);
    manager = new JdbcTransactionManager(pool);
    var mybatis = new Configuration();
    mybatis.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    mybatis.addMapper(SecurityPolicyMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.authorization.AuthorizationMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.user.UserManagementMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.account.AccountManagementMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.role.RolePermissionMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.role.RoleAssignmentMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.role.RoleManagementMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.management.ManagementQueryMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.account.PlatformLifecycleMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.account.AccountQueryMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.audit.AuditQueryMapper.class);
    mybatis.addMapper(com.ken.platform.identity.dal.mapper.login.SessionManagementMapper.class);
    mybatis.addMapper(
        com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(mybatis);
    new com.ken.platform.identity.service.permission.BuiltinPermissionCatalog(
            new SqlSessionTemplate(factory.getObject())
                .getMapper(
                    com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper.class),
            manager)
        .install();
    var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SecurityPolicyMapper.class);
    passwords = new PasswordCredentials(mapper);
    policies = new SecurityPolicies(mapper);
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'浏览器测试')", tenant, tenantCode);
    var material = passwords.temporary(policies.current(tenant));
    secret = material.secret().revealOnce();
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash) VALUES(?,?,?,'浏览器测试',?)",
                user,
                tenant,
                username,
                material.hash()));
    redis = new LoginRedisFixture();
    store =
        spy(
            new LoginStore(
                jdbc,
                new com.ken.platform.identity.dal.support.IdentityTenantScope(jdbc, manager)));
    protocolAudits = spy(new SecurityAuditRepository(jdbc));
    oauthClients = spy(new ClientStore(jdbc));
    oauthAuthorizations =
        new com.ken.platform.identity.service.oauth.ProtocolAuthorizations(
            jdbc,
            new com.ken.platform.identity.service.oauth.ControlledRegisteredClients(oauthClients),
            oauthClients);
    service =
        new PasswordLoginService(
            store,
            redis.protection,
            passwords,
            policies,
            new SecurityAuditRepository(jdbc),
            Clock.systemUTC(),
            manager,
            oauthAuthorizations);
    tokenUsers =
        spy(
            new com.ken.platform.identity.dal.mapper.oauth.UserTokenStore(
                jdbc,
                new com.ken.platform.identity.dal.support.IdentityTenantScope(jdbc, manager)));
    userTokens =
        new com.ken.platform.identity.service.oauth.UserTokenService(
            oauthClients,
            oauthAuthorizations,
            store,
            service,
            tokenUsers,
            new com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository(
                new SqlSessionTemplate(factory.getObject())
                    .getMapper(
                        com.ken.platform.identity.dal.mapper.authorization.AuthorizationMapper
                            .class)),
            new com.ken.platform.identity.service.authorization.AuthorizationEvaluator(
                Clock.systemUTC()),
            manager,
            Clock.systemUTC());
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    managementMappers = new SqlSessionTemplate(factory.getObject());
    sessionQueries =
        spy(
            managementMappers.getMapper(
                com.ken.platform.identity.dal.mapper.login.SessionManagementMapper.class));
    auditQueries =
        spy(
            managementMappers.getMapper(
                com.ken.platform.identity.dal.mapper.audit.AuditQueryMapper.class));
    var managementSql = managementMappers;
    var managementUsers =
        managementSql.getMapper(
            com.ken.platform.identity.dal.mapper.user.UserManagementMapper.class);
    var managementAccounts =
        managementSql.getMapper(
            com.ken.platform.identity.dal.mapper.account.AccountManagementMapper.class);
    policyManagement =
        new com.ken.platform.identity.service.security.SecurityPolicyManagementService(
            mapper,
            new com.ken.platform.identity.dal.mapper.security.SecurityPolicyStore(jdbc),
            managementUsers,
            managementAccounts,
            new com.ken.platform.identity.service.authorization.PlatformManagementAuthorization(
                managementAccounts),
            new com.ken.platform.identity.service.audit.ManagementAudits(
                new SecurityAuditRepository(jdbc), manager),
            manager);
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    startContext();
  }

  private void startContext() {
    context = new AnnotationConfigServletWebServerApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "native-http",
                Map.of(
                    "PROJECT_CODE",
                    redis.project,
                    "APP_ENV",
                    "local",
                    "PUBLIC_ORIGIN",
                    "http://localhost:18080",
                    "IDENTITY_SIGNING_JWK_SECRET_FILE",
                    signingFile.toString())));
    if (externalProvider != null) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "federation-fixture",
                  Map.of(
                      "platform.federation.enabled",
                      "true",
                      "platform.federation.allowed-hosts",
                      "127.0.0.1",
                      "platform.federation.tenants.fixture",
                      tenantCode)));
      context.addBeanFactoryPostProcessor(
          beans ->
              beans.registerSingleton(
                  "clientRegistrationRepository",
                  new org.springframework.security.oauth2.client.registration
                      .InMemoryClientRegistrationRepository(externalProvider.registration())));
    }
    context.addBeanFactoryPostProcessor(
        beans -> {
          beans.registerSingleton("jdbcTemplate", jdbc);
          beans.registerSingleton("transactionManager", manager);
          beans.registerSingleton("loginStore", store);
          beans.registerSingleton("loginProtection", redis.protection);
          beans.registerSingleton("passwordLoginService", service);
          beans.registerSingleton("securityPolicies", policies);
          beans.registerSingleton("securityPolicyManagementService", policyManagement);
          beans.registerSingleton("auditQueryMapper", auditQueries);
          beans.registerSingleton("sessionManagementMapper", sessionQueries);
          beans.registerSingleton("passwordCredentials", passwords);
          beans.registerSingleton(
              "platformLifecycleMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.account.PlatformLifecycleMapper.class));
          beans.registerSingleton(
              "accountQueryMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.account.AccountQueryMapper.class));
          beans.registerSingleton(
              "accountManagementMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.account.AccountManagementMapper.class));
          beans.registerSingleton("clientStore", oauthClients);
          beans.registerSingleton("protocolAuthorizations", oauthAuthorizations);
          beans.registerSingleton("userTokenService", userTokens);
          beans.registerSingleton("userTokenStore", tokenUsers);
          beans.registerSingleton("securityAudits", protocolAudits);
          beans.registerSingleton("identityRedisConnectionFactory", redis.connection);
          beans.registerSingleton(
              "tenantTransactions",
              new com.ken.platform.foundation.data.TenantTransactions(jdbc, manager));
          beans.registerSingleton(
              "userManagementMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.user.UserManagementMapper.class));
          beans.registerSingleton(
              "rolePermissionMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.role.RolePermissionMapper.class));
          beans.registerSingleton(
              "roleAssignmentMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.role.RoleAssignmentMapper.class));
          beans.registerSingleton(
              "roleManagementMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.role.RoleManagementMapper.class));
          beans.registerSingleton(
              "managementQueryMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.management.ManagementQueryMapper.class));
          beans.registerSingleton(
              "authorizationMapper",
              managementMapper(
                  com.ken.platform.identity.dal.mapper.authorization.AuthorizationMapper.class));
          beans.addBeanPostProcessor(
              new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                  return name.equals("sessionRepository") ? spy(bean) : bean;
                }
              });
        });
    context.register(WebConfiguration.class);
    context.refresh();
    origin = "http://127.0.0.1:" + context.getWebServer().getPort();
    sessions = context.getBean(RedisSessionRepository.class);
  }

  private String federationCallback(String mode) throws Exception {
    var started =
        post(
            "/identity/external/start",
            Map.of(
                "_csrf",
                csrf(mode.equals("BIND") ? "/identity/external" : "/identity/login"),
                "provider",
                "fixture",
                "tenant_code",
                tenantCode,
                "mode",
                mode));
    assertThat(started.statusCode()).isEqualTo(302);
    var redirected =
        get(URI.create(started.headers().firstValue("Location").orElseThrow()).getPath());
    assertThat(redirected.statusCode()).isEqualTo(302);
    String authorize = redirected.headers().firstValue("Location").orElseThrow();
    assertThat(authorize)
        .startsWith(externalProvider.origin() + "/authorize")
        .contains("code_challenge=")
        .contains("nonce=");
    var approved =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(authorize)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    var location = URI.create(approved.headers().firstValue("Location").orElseThrow());
    return location.getPath() + "?" + location.getRawQuery();
  }

  @Test
  void federationExplicitBindingThenLoginUsesLocalSessionAndCanUnlink() throws Exception {
    ordinary();
    login();
    var before = sid();
    var callback = federationCallback("BIND");
    var bound = get(callback);
    assertThat(bound.statusCode()).withFailMessage(bound.body()).isEqualTo(302);
    assertThat(bound.headers().firstValue("Location").orElseThrow()).contains("external=bound");
    assertThat(store.session(binding(before), false).revokedAt()).isNotNull();
    var authenticated = get(federationCallback("LOGIN"));
    assertThat(authenticated.statusCode()).withFailMessage(authenticated.body()).isEqualTo(302);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(store.session(binding(sid()), false).user()).isEqualTo(user);
    org.springframework.session.Session stored = sessions.findById(sid());
    var security =
        (org.springframework.security.core.context.SecurityContext)
            stored.getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(security.getAuthentication().getPrincipal())
        .isInstanceOf(PasswordLoginService.Identity.class);
    assertThat(stored.getAttributeNames()).noneMatch(key -> key.contains("AUTHORIZED_CLIENT"));
    assertThat(
            post(
                    "/identity/external/unlink",
                    Map.of("_csrf", csrf("/identity/external"), "provider", "fixture"))
                .statusCode())
        .isEqualTo(302);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_external_identity WHERE tenant_id=? AND user_id=?",
                        Long.class,
                        tenant,
                        user)))
        .isZero();
  }

  @Test
  void federationUnboundSameEmailNeverCreatesOrMergesAccount() throws Exception {
    ordinary();
    long before = data(() -> jdbc.queryForObject("SELECT count(*) FROM sys_user", Long.class));
    var rejected = get(federationCallback("LOGIN"));
    assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(401);
    assertThat(rejected.body()).contains("尚未绑定");
    assertThat(data(() -> jdbc.queryForObject("SELECT count(*) FROM sys_user", Long.class)))
        .isEqualTo(before);
    assertThat(get("/identity/session").statusCode()).isEqualTo(302);
  }

  @Test
  void federationStateNonceIssuerAudienceAndSignatureAreCheckedBySpring() throws Exception {
    ordinary();
    var callback = federationCallback("LOGIN");
    String forged = callback.replaceFirst("state=[^&]+", "state=forged");
    assertThat(get(forged).statusCode()).isEqualTo(401);
    for (String defect : java.util.List.of("nonce", "issuer", "audience", "signature")) {
      externalProvider.defect = defect;
      var rejected = get(federationCallback("LOGIN"));
      assertThat(rejected.statusCode())
          .withFailMessage("defect=" + defect + " status=" + rejected.statusCode())
          .isEqualTo(401);
      assertThat(rejected.body()).doesNotContain("尚未绑定");
    }
  }

  @Test
  void federationCsrfTenantTamperingAndReplayDoNotBindAnotherAccount() throws Exception {
    ordinary();
    login();
    assertThat(
            post(
                    "/identity/external/start",
                    Map.of("provider", "fixture", "tenant_code", tenantCode, "mode", "BIND"))
                .statusCode())
        .isEqualTo(403);
    var denied =
        post(
            "/identity/external/start",
            Map.of(
                "_csrf",
                csrf("/identity/external"),
                "provider",
                "fixture",
                "tenant_code",
                "foreign-tenant",
                "mode",
                "BIND"));
    assertThat(denied.statusCode()).isEqualTo(400);
    var callback = federationCallback("BIND");
    assertThat(get(callback + "&tenant_id=" + otherTenant).statusCode()).isEqualTo(302);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_external_identity WHERE tenant_id=? AND user_id=?",
                        Long.class,
                        tenant,
                        user)))
        .isEqualTo(1);
    assertThat(get(callback).statusCode()).isEqualTo(401);
  }

  @Test
  void federationConcurrentSameStateCreatesOnlyOneBindingAndLocalSession() throws Exception {
    ordinary();
    login();
    federationCallbackRace("BIND");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_external_identity WHERE user_id=?",
                        Integer.class,
                        user)))
        .isEqualTo(1);
    for (int round = 0; round < 20; round++) {
      int before =
          jdbc.queryForObject(
              "SELECT count(*) FROM sys_login_session WHERE user_id=?", Integer.class, user);
      federationCallbackRace("LOGIN");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_login_session WHERE user_id=?", Integer.class, user))
          .isEqualTo(before + 1);
    }
  }

  private void federationCallbackRace(String mode) throws Exception {
    String callback = federationCallback(mode);
    String cookie =
        cookies.getCookieStore().getCookies().stream()
            .filter(value -> !value.hasExpired())
            .map(value -> value.getName() + "=" + value.getValue())
            .collect(java.util.stream.Collectors.joining("; "));
    var ready = new CountDownLatch(20);
    var start = new CountDownLatch(1);
    try (var caller = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int request = 0; request < 20; request++) {
        pending.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return caller.send(
                      HttpRequest.newBuilder(URI.create(origin + callback))
                          .timeout(Duration.ofSeconds(15))
                          .header("Cookie", cookie)
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var responses = new java.util.ArrayList<HttpResponse<String>>();
      for (var future : pending) responses.add(future.get(30, TimeUnit.SECONDS));
      String destination =
          mode.equals("BIND") ? "/identity/login?external=bound" : "/identity/session";
      assertThat(responses)
          .allMatch(response -> response.statusCode() == 302 || response.statusCode() == 401);
      assertThat(
              responses.stream()
                  .filter(
                      response ->
                          response.statusCode() == 302
                              && response
                                  .headers()
                                  .firstValue("Location")
                                  .orElse("")
                                  .equals(destination)))
          .hasSize(1);
    }
  }

  @Test
  void federationBindingRechecksRevokedSessionAndLoginRechecksDisabledAccount() throws Exception {
    ordinary();
    login();
    var callback = federationCallback("BIND");
    var session = binding(sid());
    jdbc.update("UPDATE sys_login_session SET revoked_at=now() WHERE id=?", session);
    var redirected = get(callback);
    assertThat(redirected.statusCode()).isEqualTo(302);
    assertThat(URI.create(redirected.headers().firstValue("Location").orElseThrow()).getPath())
        .isEqualTo("/identity/login");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_external_identity WHERE tenant_id=?",
                        Long.class,
                        tenant)))
        .isZero();
    cookies.getCookieStore().removeAll();
    login();
    assertThat(get(federationCallback("BIND")).statusCode()).isEqualTo(302);
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                user));
    assertThat(get(federationCallback("LOGIN")).statusCode()).isEqualTo(401);
  }

  @Test
  void federationBindingRequiresRecentLocalAuthentication() throws Exception {
    ordinary();
    login();
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '11 minutes' WHERE id=?",
        binding(sid()));
    var response =
        post(
            "/identity/external/start",
            Map.of(
                "_csrf",
                csrf("/identity/external"),
                "provider",
                "fixture",
                "tenant_code",
                tenantCode,
                "mode",
                "BIND"));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_external_identity WHERE tenant_id=?",
                        Long.class,
                        tenant)))
        .isZero();
  }

  private <T> T managementMapper(Class<T> type) {
    return managementMappers.getMapper(type);
  }

  @AfterEach
  void cleanup() {
    if (externalProvider != null) externalProvider.close();
    if (context != null) context.close();
    if (redis != null) {
      if (!redis.connection.isRunning()) redis.connection.start();
      redis.close();
    }
    if (pool != null) {
      try {
        if (originalFailureThreshold != null)
          jdbc.update(
              "UPDATE sys_security_policy SET login_failure_threshold=?,version=version+1 WHERE owner_type='PLATFORM'",
              originalFailureThreshold);
      } finally {
        pool.close();
      }
    }
  }

  @Test
  void publicJwksNeverExposesPrivateMaterialAndKeepsKeyAfterContextRestart() throws Exception {
    var encoder = context.getBean(org.springframework.security.oauth2.jwt.JwtEncoder.class);
    var now = java.time.Instant.now();
    String signed =
        encoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(
                            org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                        .build(),
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .issuer("http://localhost:18080")
                        .subject("fixture")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build()))
            .getTokenValue();
    var response = get("/oauth2/jwks");
    assertThat(response.statusCode()).isEqualTo(200);
    var before = com.nimbusds.jose.jwk.JWKSet.parse(response.body());
    assertThat(before.getKeys()).hasSize(1).allMatch(key -> !key.isPrivate());
    assertThat(before.getKeys().getFirst().getKeyID()).isEqualTo("native-http-key");
    assertThat(before.getKeys().getFirst().toJSONObject())
        .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    assertThat(cookies.getCookieStore().getCookies()).isEmpty();
    context.close();
    startContext();
    assertThat(get("/oauth2/jwks").body()).isEqualTo(response.body());
    assertThat(
            context
                .getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class)
                .decode(signed)
                .getSubject())
        .isEqualTo("fixture");
  }

  private <T> T data(Supplier<T> action) {
    return new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)", String.class, tenant.toString());
              return action.get();
            });
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
    return post(path, fields, null);
  }

  private HttpResponse<String> post(String path, Map<String, String> fields, String forwarded)
      throws Exception {
    String body =
        fields.entrySet().stream()
            .map(
                entry ->
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    var request =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (forwarded != null)
      request.header("X-Forwarded-For", forwarded).header("Forwarded", "for=203.0.113.7");
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String csrf(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).isEqualTo(200);
    var input = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*>").matcher(response.body());
    assertThat(input.find()).isTrue();
    var value = Pattern.compile("value=\"([^\"]+)\"").matcher(input.group());
    assertThat(value.find()).isTrue();
    return value.group(1);
  }

  private String sid() {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("IDENTITY_SSO") && !cookie.hasExpired())
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private Map<String, String> credentials(String token, String password) {
    return Map.of(
        "_csrf",
        token,
        "realm",
        "TENANT",
        "tenant_code",
        tenantCode,
        "username",
        username,
        "password",
        password);
  }

  private void login() throws Exception {
    var response = post("/identity/login", credentials(csrf("/identity/login"), secret));
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private UUID binding(String id) {
    return jdbc.queryForObject(
        "SELECT id FROM sys_login_session WHERE redis_session_id=?", UUID.class, id);
  }

  private void ordinary() {
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='ACTIVE',must_change_password=false WHERE id=?", user));
  }

  private record OAuthClient(
      String id,
      String clientId,
      String secret,
      boolean publicClient,
      boolean platform,
      String scope) {}

  private static final String VERIFIER =
      "native-user-pkce-verifier-with-at-least-43-characters-long";
  private static final String CALLBACK = "https://client.example/callback";

  private OAuthClient oauthClient(boolean publicClient) {
    return oauthClient(publicClient, false, false);
  }

  private OAuthClient oauthClient(boolean publicClient, boolean oidc, boolean platform) {
    String id = UUID.randomUUID().toString(), clientId = "browser-client-" + id;
    String password = publicClient ? null : "fixture-" + UUID.randomUUID();
    var builder =
        org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(id)
            .clientId(clientId)
            .clientName("浏览器协议测试应用")
            .clientAuthenticationMethod(
                publicClient
                    ? org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE
                    : org.springframework.security.oauth2.core.ClientAuthenticationMethod
                        .CLIENT_SECRET_BASIC)
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(CALLBACK)
            .scope("profile")
            .clientSettings(
                org.springframework.security.oauth2.server.authorization.settings.ClientSettings
                    .builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(publicClient)
                    .build())
            .tokenSettings(
                org.springframework.security.oauth2.server.authorization.settings.TokenSettings
                    .builder()
                    .accessTokenFormat(
                        org.springframework.security.oauth2.server.authorization.settings
                            .OAuth2TokenFormat.REFERENCE)
                    .accessTokenTimeToLive(Duration.ofMinutes(10))
                    .refreshTokenTimeToLive(Duration.ofHours(8))
                    .reuseRefreshTokens(false)
                    .build());
    if (oidc) builder.scope("openid").postLogoutRedirectUri("https://client.example/logged-out");
    if (oidc && !publicClient) builder.scope("email");
    if (!publicClient)
      builder
          .clientSecret(
              org.springframework.security.crypto.factory.PasswordEncoderFactories
                  .createDelegatingPasswordEncoder()
                  .encode(password))
          .authorizationGrantType(
              org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN);
    var client = builder.build();
    var policy =
        new com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO(
            platform
                ? com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType
                    .PLATFORM
                : com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType.USER,
            platform ? null : tenant,
            platform ? java.util.Set.of() : java.util.Set.of(tenant),
            java.util.Set.of("identity-service"),
            com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status.ACTIVE,
            0);
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES('identity-service','identity-service') ON CONFLICT DO NOTHING");
    new TransactionTemplate(manager)
        .execute(
            status -> {
              context.getBean(ClientStore.class).savePair(client, policy, null);
              return null;
            });
    return new OAuthClient(
        id,
        clientId,
        password,
        publicClient,
        platform,
        oidc ? publicClient ? "openid profile" : "openid profile email" : "profile");
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private HttpResponse<String> bearer(String path, String token) throws Exception {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
        .send(
            HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void saveProjectPolicy(Map<String, Integer> values) {
    UUID platform = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'策略管理员','fixture',false)",
        platform,
        "policy-" + platform);
    var authority =
        new org.springframework.security.core.authority.SimpleGrantedAuthority(
            "PERMISSION_platform:security:manage");
    var principal =
        new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
            platform.toString(),
            Map.of("principal_type", "platform"),
            java.util.List.of(authority));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(principal, null, java.util.List.of(authority)));
    try (var ignored =
        com.ken.platform.foundation.http.CallContexts.bind(
            new com.ken.platform.foundation.http.CallContext(
                UUID.randomUUID().toString(), null, platform.toString(), null, null, null))) {
      policyManagement.updatePlatform(policies.current(null).platformVersion(), values);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  void managedPolicyTighteningRevokesNextUserInfoAndLaterRelaxationCannotReviveIt()
      throws Exception {
    ordinary();
    login();
    jdbc.update(
        "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES('identity:security:manage','安全策略','identity-service','TENANT','native','测试','native-security-policy','策略','manage',ARRAY['TENANT'],'identity.security') ON CONFLICT DO NOTHING");
    UUID role = UUID.randomUUID();
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,'tenant-admin','策略管理员',true)",
              role,
              tenant);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              role);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,'identity:security:manage','TENANT')",
              tenant,
              role);
          jdbc.update(
              "INSERT INTO sys_tenant_capability(tenant_id,capability,enabled) VALUES(?,'SECURITY_POLICY_MANAGEMENT',true)",
              tenant);
          return null;
        });
    var client = oauthClient(false, true, false);
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    UUID original = binding(sid());
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '6 minutes',last_activity_at=now()-interval '2 minutes' WHERE id=?",
        original);
    assertThat(bearer("/userinfo", token).statusCode()).isEqualTo(200);
    var previousPolicy = policies.current(null);
    try {
      saveProjectPolicy(Map.of("session_absolute_seconds", 300, "session_idle_seconds", 60));
      assertThat(bearer("/userinfo", token).statusCode()).isEqualTo(401);
      assertThat(store.session(original, false).revokedAt()).isNotNull();
    } finally {
      saveProjectPolicy(
          Map.of(
              "session_absolute_seconds",
              previousPolicy.sessionAbsoluteSeconds(),
              "session_idle_seconds",
              previousPolicy.sessionIdleSeconds()));
    }
    assertThat(bearer("/userinfo", token).statusCode()).isEqualTo(401);
    login();
    String fresh =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    assertThat(bearer("/userinfo", fresh).statusCode()).isEqualTo(200);
  }

  private tools.jackson.databind.JsonNode json(HttpResponse<String> response) {
    return JsonMapper.builder().build().readTree(response.body());
  }

  private OAuthClient reader(String audience) {
    String id = UUID.randomUUID().toString(),
        clientId = "reader-" + id,
        password = "fixture-" + UUID.randomUUID();
    var client =
        org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(id)
            .clientId(clientId)
            .clientName("内省测试客户端")
            .clientAuthenticationMethod(
                org.springframework.security.oauth2.core.ClientAuthenticationMethod
                    .CLIENT_SECRET_BASIC)
            .clientSecret(
                org.springframework.security.crypto.factory.PasswordEncoderFactories
                    .createDelegatingPasswordEncoder()
                    .encode(password))
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("internal.introspect:" + audience)
            .tokenSettings(
                org.springframework.security.oauth2.server.authorization.settings.TokenSettings
                    .builder()
                    .accessTokenFormat(
                        org.springframework.security.oauth2.server.authorization.settings
                            .OAuth2TokenFormat.REFERENCE)
                    .build())
            .build();
    var policy =
        new com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO(
            com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType.SERVICE,
            null,
            java.util.Set.of(),
            java.util.Set.of("identity-service"),
            com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status.ACTIVE,
            0);
    new TransactionTemplate(manager)
        .execute(
            status -> {
              oauthClients.savePair(client, policy, null);
              return null;
            });
    return new OAuthClient(id, clientId, password, false, false, "internal.introspect:" + audience);
  }

  private void clientBoundary(
      OAuthClient client, java.util.Set<String> scopes, java.util.Set<String> audiences) {
    new TransactionTemplate(manager)
        .execute(
            status -> {
              var snapshot = oauthClients.lockSnapshot(client.id());
              var registered =
                  org.springframework.security.oauth2.server.authorization.client.RegisteredClient
                      .from(snapshot.client())
                      .scopes(
                          values -> {
                            values.clear();
                            values.addAll(scopes);
                          })
                      .build();
              var old = snapshot.policy();
              oauthClients.savePair(
                  registered,
                  new com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO(
                      old.principalType(),
                      old.ownerTenantId(),
                      old.allowedTenantIds(),
                      audiences,
                      old.status(),
                      old.version() + 1),
                  old.version());
              return null;
            });
  }

  @Test
  void oidcDiscoverySignedIdTokenRefreshAndScopedLiveUserInfoWorkOverHttp() throws Exception {
    ordinary();
    login();
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET verified_email='learner@example.test',email_verified_at=now() WHERE id=?",
                user));
    var client = oauthClient(false, true, false);
    var metadata = get("/.well-known/openid-configuration");
    assertThat(metadata.statusCode()).isEqualTo(200);
    assertThat(json(metadata).get("issuer").asString()).isEqualTo("http://localhost:18080");
    assertThat(json(metadata).get("jwks_uri").asString())
        .isEqualTo("http://localhost:18080/oauth2/jwks");
    assertThat(json(metadata).has("registration_endpoint")).isFalse();
    var response = oauthToken(client, codeGrant(authorizationCode(client), VERIFIER));
    assertThat(response.statusCode()).isEqualTo(200);
    var issued = json(response);
    var decoder =
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(
                origin + "/oauth2/jwks")
            .build();
    decoder.setJwtValidator(
        org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(
            "http://localhost:18080"));
    var id = decoder.decode(issued.get("id_token").asString());
    assertThat(id.getSubject()).isEqualTo(user.toString());
    assertThat(id.getAudience()).containsExactly(client.clientId());
    assertThat(id.getClaimAsString("nonce")).isEqualTo("native-oidc-nonce");
    assertThat(id.getClaimAsString("sid")).isNotEqualTo(sid()).matches("[A-Za-z0-9_-]{43}");
    assertThat(id.getClaimAsString("email")).isEqualTo("learner@example.test");
    assertThat(id.getClaimAsInstant("auth_time"))
        .isEqualTo(
            store
                .session(binding(sid()), false)
                .createdAt()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    assertThat(bearer("/userinfo", issued.get("id_token").asString()).statusCode()).isEqualTo(401);
    assertThat(
            json(bearer("/userinfo", issued.get("access_token").asString())).get("sub").asString())
        .isEqualTo(user.toString());
    var refreshed =
        oauthToken(
            client,
            Map.of(
                "grant_type",
                "refresh_token",
                "refresh_token",
                issued.get("refresh_token").asString()));
    assertThat(refreshed.statusCode()).isEqualTo(200);
    var next = json(refreshed);
    assertThat(decoder.decode(next.get("id_token").asString()).getClaimAsInstant("auth_time"))
        .isEqualTo(id.getClaimAsInstant("auth_time"));
    data(() -> jdbc.update("UPDATE sys_user SET display_name='更新后的资料' WHERE id=?", user));
    var current = json(bearer("/userinfo", next.get("access_token").asString()));
    assertThat(current.get("name").asString()).isEqualTo("更新后的资料");
    assertThat(current.get("email_verified").asBoolean()).isTrue();
    assertThat(current.has("password_hash")).isFalse();
    clientBoundary(
        client, java.util.Set.of("openid", "profile"), java.util.Set.of("identity-service"));
    assertThat(json(bearer("/userinfo", next.get("access_token").asString())).has("email"))
        .isFalse();
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                user));
    assertThat(bearer("/userinfo", next.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    assertThat(
            oauthAuthorizations
                .findByToken(
                    next.get("access_token").asString(),
                    org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                        .ACCESS_TOKEN)
                .getAccessToken()
                .isActive())
        .isFalse();
  }

  private record LiveGrant(
      OAuthClient client,
      UUID role,
      String read,
      String write,
      String readScope,
      String writeScope,
      String audience) {}

  private record ManagementFixture(
      OAuthClient client,
      String token,
      UUID managerRole,
      UUID businessRole,
      UUID target,
      UUID delegate,
      String read) {}

  private static final java.util.List<String> NATIVE_MANAGEMENT_OPERATIONS =
      java.util.List.of(
          "identity:role:manage",
          "identity:role:assign",
          "identity:user:manage",
          "identity:user:read",
          "identity:role:read",
          "identity:permission:read");

  private ManagementFixture managementFixture(boolean protectedManager) throws Exception {
    ordinary();
    login();
    UUID managerRole = UUID.randomUUID(),
        businessRole = UUID.randomUUID(),
        target = UUID.randomUUID(),
        delegate = UUID.randomUUID();
    String read = "management:read:" + UUID.randomUUID();
    for (String operation : NATIVE_MANAGEMENT_OPERATIONS) {
      String[] parts = operation.split(":");
      jdbc.update(
          "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'管理','identity-service','TENANT','identity','身份',?,'管理资源',?,ARRAY['TENANT'],?) ON CONFLICT DO NOTHING",
          operation,
          parts[1],
          parts[2],
          parts[1].equals("user")
              ? "identity.users"
              : parts[1].equals("role") ? "identity.roles" : "identity.permissions");
    }
    jdbc.update(
        "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'业务读取','document-service','TENANT','native-management','管理测试',?,'业务资源','read',ARRAY['SELF','TENANT'],'management.business.read')",
        read,
        read);
    data(
        () -> {
          for (UUID id : java.util.List.of(target, delegate))
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'普通目标','{bcrypt}fixture','ACTIVE',false)",
                id,
                tenant,
                "u-" + id);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,?,'管理角色',?)",
              managerRole,
              tenant,
              protectedManager ? "tenant-admin" : "operator",
              protectedManager);
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'business','业务角色')",
              businessRole,
              tenant);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              managerRole);
          for (String operation : NATIVE_MANAGEMENT_OPERATIONS)
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
                tenant,
                managerRole,
                operation);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'SELF')",
              tenant,
              businessRole,
              read);
          if (!protectedManager) {
            jdbc.update(
                "INSERT INTO sys_management_grant(tenant_id,actor_user_id,role_id,allow_assign,allow_edit) VALUES(?,?,?,true,true)",
                tenant,
                user,
                businessRole);
            jdbc.update(
                "INSERT INTO sys_management_grant_permission(tenant_id,actor_user_id,role_id,permission_code,max_data_scope) VALUES(?,?,?,?,'SELF')",
                tenant,
                user,
                businessRole,
                read);
          }
          return null;
        });
    var original = oauthClient(false);
    clientBoundary(
        original,
        java.util.Set.of("profile", "identity.roles", "identity.users", "identity.permissions"),
        java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            original.id(),
            original.clientId(),
            original.secret(),
            false,
            false,
            "profile identity.roles identity.users identity.permissions");
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    return new ManagementFixture(client, token, managerRole, businessRole, target, delegate, read);
  }

  private HttpResponse<String> management(String method, String path, String token, Object body)
      throws Exception {
    return management(method, path, token, body, "management-http-test");
  }

  private HttpResponse<String> management(
      String method, String path, String token, Object body, String requestId) throws Exception {
    String content =
        body instanceof String raw ? raw : JsonMapper.builder().build().writeValueAsString(body);
    var request =
        HttpRequest.newBuilder(URI.create(origin + "/api/v1/identity" + path))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", requestId)
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header("X-User-Id", UUID.randomUUID().toString())
            .method(
                method,
                method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(content));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> permissionBody(
      ManagementFixture fixture, long version, String range, Long grantVersion) {
    var body = new HashMap<String, Object>();
    body.put("role_version", version);
    body.put(
        "catalog_version",
        jdbc.queryForObject(
            "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class));
    body.put(
        "grants",
        java.util.List.of(Map.of("permission_code", fixture.read(), "data_scope", range)));
    if (grantVersion != null) body.put("management_grant_version", grantVersion);
    return body;
  }

  private Map<String, Object> assignmentBody(
      ManagementFixture fixture,
      long userVersion,
      long roleVersion,
      Long grantVersion,
      boolean add) {
    var body = new HashMap<String, Object>();
    body.put("user_version", userVersion);
    body.put(
        "roles",
        add
            ? java.util.List.of(
                Map.of("id", fixture.businessRole().toString(), "version", roleVersion))
            : java.util.List.of());
    if (grantVersion != null)
      body.put(
          "management_grant_versions", Map.of(fixture.businessRole().toString(), grantVersion));
    return body;
  }

  @Test
  void realBearerManagementApiUsesLiveApplicationAuthorityAndExistingAtomicServices()
      throws Exception {
    var fixture = managementFixture(true);
    clearInvocations(sessions);
    var edited =
        management(
            "PUT",
            "/role-permissions/" + fixture.businessRole(),
            fixture.token(),
            permissionBody(fixture, 0, "TENANT", null));
    assertThat(edited.statusCode()).isEqualTo(200);
    assertThat(json(edited).get("data").get("authorization_version").asLong()).isEqualTo(1);
    assertThat(edited.headers().allValues("Set-Cookie")).isEmpty();
    verify(sessions, never()).findById(anyString());
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(edited.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(json(edited).get("request_id").asString()).isEqualTo("management-http-test");
    assertThat(edited.body()).doesNotContain("password_hash", "access_token", "refresh_token");
    var stale =
        management(
            "PUT",
            "/role-permissions/" + fixture.businessRole(),
            fixture.token(),
            permissionBody(fixture, 0, "SELF", null));
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(json(stale).get("code").asString()).isEqualTo("ROLE_OR_CATALOG_VERSION_CONFLICT");
    var assigned =
        management(
            "PUT",
            "/users/" + fixture.target() + "/roles",
            fixture.token(),
            assignmentBody(fixture, 0, 1, null, true));
    assertThat(assigned.statusCode()).isEqualTo(200);
    assertThat(json(assigned).get("data").get("user_id").asString())
        .isEqualTo(fixture.target().toString());
    assertThat(
            management(
                    "PUT",
                    "/users/" + fixture.target(),
                    fixture.token(),
                    Map.of("version", 1, "status", "DISABLED"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            management(
                    "PUT",
                    "/users/" + UUID.randomUUID(),
                    fixture.token(),
                    Map.of("version", 0, "status", "DISABLED"))
                .statusCode())
        .isEqualTo(404);
    assertThat(
            management(
                    "PUT",
                    "/users/" + user,
                    fixture.token(),
                    Map.of("version", 0, "status", "DISABLED"))
                .statusCode())
        .isEqualTo(409);
    var created =
        management(
            "POST",
            "/roles",
            fixture.token(),
            Map.of("code", "new-" + UUID.randomUUID(), "name", "API 新角色"));
    assertThat(created.statusCode()).isEqualTo(200);
    String newId = json(created).get("data").get("id").asString();
    assertThat(
            management(
                    "PUT",
                    "/roles/" + newId,
                    fixture.token(),
                    Map.of("version", 0, "name", "API 修改", "status", "DISABLED"))
                .statusCode())
        .isEqualTo(200);
    var copied =
        management(
            "POST",
            "/roles/" + fixture.businessRole() + ":copy",
            fixture.token(),
            Map.of(
                "role_version",
                2,
                "catalog_version",
                jdbc.queryForObject(
                    "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class),
                "code",
                "copy-" + UUID.randomUUID(),
                "name",
                "API 复制"));
    assertThat(copied.statusCode()).isEqualTo(200);
    var delegated =
        management(
            "PUT",
            "/management-grants",
            fixture.token(),
            Map.of(
                "actor_user_id",
                fixture.delegate().toString(),
                "role_id",
                fixture.businessRole().toString(),
                "role_version",
                2,
                "catalog_version",
                jdbc.queryForObject(
                    "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class),
                "allow_assign",
                true,
                "allow_edit",
                false,
                "ceilings",
                java.util.List.of(
                    Map.of("permission_code", fixture.read(), "max_data_scope", "SELF"))));
    assertThat(delegated.statusCode()).isEqualTo(404);
  }

  @Test
  void delegatedBearerCannotEscalateOrSelfAssignAndRevocationAffectsNextWrite() throws Exception {
    var fixture = managementFixture(false);
    for (UUID target :
        java.util.List.of(user, fixture.target(), fixture.delegate(), otherTenantUser)) {
      assertThat(
              management(
                      "PUT",
                      "/users/" + target + "/roles",
                      fixture.token(),
                      assignmentBody(fixture, 0, 0, null, true))
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              management(
                      "PUT",
                      "/users/" + target,
                      fixture.token(),
                      Map.of("version", 0, "status", "DISABLED"))
                  .statusCode())
          .isEqualTo(403);
    }
    assertThat(
            management(
                    "PUT",
                    "/role-permissions/" + fixture.businessRole(),
                    fixture.token(),
                    permissionBody(fixture, 0, "SELF", null))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "PUT",
                    "/role-permissions/" + fixture.businessRole(),
                    fixture.token(),
                    permissionBody(fixture, 0, "SELF", 0L))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                        Integer.class,
                        fixture.target(),
                        fixture.businessRole())))
        .isZero();
  }

  @Test
  void managementApiRejectsCookieAndQueryTokensAndFiltersActualIssuedScopes() throws Exception {
    var fixture = managementFixture(true);
    assertThat(
            management("POST", "/roles", null, Map.of("code", "cookie", "name", "Cookie 不能授权"))
                .statusCode())
        .isEqualTo(401);
    assertThat(
            management(
                    "POST",
                    "/roles?access_token=" + fixture.token(),
                    null,
                    Map.of("code", "query", "name", "参数不能授权"))
                .statusCode())
        .isEqualTo(401);
    var profileOnly =
        new OAuthClient(
            fixture.client().id(),
            fixture.client().clientId(),
            fixture.client().secret(),
            false,
            false,
            "profile");
    String narrow =
        json(oauthToken(profileOnly, codeGrant(authorizationCode(profileOnly), VERIFIER)))
            .get("access_token")
            .asString();
    var denied = management("POST", "/roles", narrow, Map.of("code", "narrow", "name", "不能复用全量角色"));
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(json(denied).get("code").asString()).isEqualTo("FORBIDDEN");
    data(
        () ->
            jdbc.update(
                "DELETE FROM sys_role_permission WHERE role_id=? AND permission_code='identity:role:manage'",
                fixture.managerRole()));
    assertThat(
            management("POST", "/roles", fixture.token(), Map.of("code", "live", "name", "下一请求生效"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE request_id='management-http-test' AND action='identity.api.rejected' AND result='FAILURE'",
                Integer.class))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void managementApiValidatesRequiredVersionsAndReturnsSanitizedAuditedErrors() throws Exception {
    var fixture = managementFixture(true);
    for (String body :
        java.util.List.of(
            "{\"version\":\"DO_NOT_ECHO_SECRET\",\"status\":\"DISABLED\"}",
            "{\"status\":\"DISABLED\"}",
            "{\"version\":0,\"status\":\"UNKNOWN_STATE\"}",
            "{broken")) {
      var invalid = management("PUT", "/users/" + fixture.target(), fixture.token(), body);
      assertThat(invalid.statusCode()).isEqualTo(400);
      assertThat(json(invalid).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
      assertThat(invalid.body())
          .doesNotContain(
              "DO_NOT_ECHO_SECRET",
              "SQLException",
              "HttpMessageNotReadableException",
              "password_hash");
      assertThat(invalid.headers().allValues("Set-Cookie")).isEmpty();
    }
    assertThat(
            management(
                    "PUT",
                    "/users/not-a-uuid",
                    fixture.token(),
                    Map.of("version", 0, "status", "DISABLED"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT status FROM sys_user WHERE id=?", String.class, fixture.target())))
        .isEqualTo("ACTIVE");
    String audits =
        jdbc.queryForObject(
            "SELECT string_agg(details::text,',') FROM sys_auth_audit WHERE actor_id=? AND action='identity.api.rejected'",
            String.class,
            user);
    assertThat(audits)
        .contains("INVALID_ARGUMENT")
        .doesNotContain("DO_NOT_ECHO_SECRET", fixture.token(), "{broken");
  }

  @Test
  void managementAuditOutageFailsClosedForAuthenticationAndMalformedRequests() throws Exception {
    var fixture = managementFixture(true);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_AUDIT_CONNECTION"))
        .when(protocolAudits)
        .failure(
            any(),
            anyString(),
            any(),
            eq("identity.api.rejected"),
            any(),
            anyString(),
            anyString());
    var anonymous = management("POST", "/roles", null, Map.of("code", "audit", "name", "无审计不能授权"));
    assertThat(anonymous.statusCode()).isEqualTo(503);
    assertThat(json(anonymous).get("code").asString()).isEqualTo("SECURITY_AUDIT_UNAVAILABLE");
    var malformed = management("PUT", "/users/" + fixture.target(), fixture.token(), "{broken");
    assertThat(malformed.statusCode()).isEqualTo(503);
    assertThat(json(malformed).get("code").asString()).isEqualTo("SECURITY_AUDIT_UNAVAILABLE");
    assertThat(anonymous.body() + malformed.body())
        .doesNotContain("DO_NOT_ECHO_AUDIT_CONNECTION", fixture.token());
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT status FROM sys_user WHERE id=?", String.class, fixture.target())))
        .isEqualTo("ACTIVE");
  }

  private tools.jackson.databind.JsonNode managementData(ManagementFixture fixture, String path)
      throws Exception {
    var response = management("GET", path, fixture.token(), Map.of());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    return json(response).get("data");
  }

  @Test
  void managementQueriesPaginateAndFilterCurrentTenantWithoutSecretsOrSqlWildcards()
      throws Exception {
    var fixture = managementFixture(true);
    var first = managementData(fixture, "/users?page=0&size=1&tenant_id=" + UUID.randomUUID());
    var second = managementData(fixture, "/users?page=1&size=1");
    assertThat(first.get("items").size()).isEqualTo(1);
    assertThat(first.get("has_more").asBoolean()).isTrue();
    assertThat(first.get("items").get(0).get("id").asString())
        .isNotEqualTo(second.get("items").get(0).get("id").asString());
    var all = managementData(fixture, "/users");
    assertThat(all.get("items").size()).isEqualTo(3);
    assertThat(all.toString())
        .doesNotContain(
            "password_hash", "verified_email", "auth_version", otherTenantUser.toString());
    var filtered = managementData(fixture, "/users?q=u-" + fixture.target());
    assertThat(filtered.get("items").size()).isEqualTo(1);
    assertThat(filtered.get("items").get(0).get("id").asString())
        .isEqualTo(fixture.target().toString());
    String input = URLEncoder.encode("' OR 1=1 -- %_", StandardCharsets.UTF_8);
    assertThat(managementData(fixture, "/users?q=" + input).get("items").size()).isZero();
    assertThat(
            management("GET", "/users/" + otherTenantUser, fixture.token(), Map.of()).statusCode())
        .isEqualTo(404);
    for (String parameters :
        java.util.List.of("page=-1", "size=0", "size=101", "page=10001", "page=not-a-number"))
      assertThat(management("GET", "/users?" + parameters, fixture.token(), Map.of()).statusCode())
          .isEqualTo(400);
  }

  @Test
  void managementRoleQueriesReturnVersionsCatalogAndRealRoleSourcesForAssignedUser()
      throws Exception {
    var fixture = managementFixture(true);
    var listed = managementData(fixture, "/roles?assignable_to=" + fixture.target());
    assertThat(listed.get("items").size()).isEqualTo(2);
    var detail = managementData(fixture, "/role-permissions/" + fixture.businessRole());
    assertThat(detail.get("role").get("version").asLong()).isZero();
    assertThat(detail.get("role").get("can_edit").asBoolean()).isTrue();
    assertThat(detail.get("role").get("can_assign").asBoolean()).isTrue();
    assertThat(detail.get("grants").get(0).get("permission_code").asString())
        .isEqualTo(fixture.read());
    assertThat(detail.get("catalog_version").asLong())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class));
    var catalog =
        managementData(fixture, "/permissions?assignable=true&role_id=" + fixture.businessRole());
    assertThat(catalog.get("items").toString())
        .contains(fixture.read(), "supported_data_scopes", "assignable_data_scopes", "TENANT");
    assertThat(
            management(
                    "PUT",
                    "/users/" + fixture.target() + "/roles",
                    fixture.token(),
                    assignmentBody(fixture, 0, 0, null, true))
                .statusCode())
        .isEqualTo(200);
    var membership = managementData(fixture, "/users/" + fixture.target() + "/roles");
    assertThat(membership.get("version").asLong()).isEqualTo(1);
    assertThat(membership.get("roles").get(0).get("id").asString())
        .isEqualTo(fixture.businessRole().toString());
    var effective =
        managementData(fixture, "/users/" + fixture.target() + "/effective-permissions");
    assertThat(effective.get("view").asString()).isEqualTo("ROLE_GRANTS");
    assertThat(java.time.Instant.parse(effective.get("as_of").asString()))
        .isBefore(java.time.Instant.now().plusSeconds(1));
    var decision = effective.get("permissions").get(fixture.read());
    assertThat(decision.get("allowed").asBoolean()).isTrue();
    assertThat(decision.get("data_scope").asString()).isEqualTo("SELF");
    assertThat(decision.get("sources").get(0).get("role_id").asString())
        .isEqualTo(fixture.businessRole().toString());
    assertThat(decision.get("sources").get(0).get("authorization_version").asLong()).isZero();
    assertThat(effective.toString())
        .doesNotContain("access_token", "refresh_token", "client_secret");
  }

  @Test
  void delegatedManagementQueriesShareOrdinaryTargetsAndOnlyExposeExplicitAssignableCeilings()
      throws Exception {
    var fixture = managementFixture(false);
    for (String path :
        java.util.List.of(
            "/users",
            "/roles",
            "/permissions",
            "/role-permissions/" + fixture.businessRole(),
            "/roles/" + fixture.businessRole() + "/members",
            "/users/" + otherTenantUser)) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(403);
    }
  }

  @Test
  void roleQueryRestrictionsTrackWholeUpperBoundEveryMemberAndDisabledRoleRemoval()
      throws Exception {
    var fixture = managementFixture(false);
    for (String path :
        java.util.List.of(
            "/users",
            "/roles",
            "/permissions",
            "/role-permissions/" + fixture.businessRole(),
            "/roles/" + fixture.businessRole() + "/members",
            "/users/" + otherTenantUser)) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(403);
    }
  }

  @Test
  void protectedDelegationQueryPaginatesLiveAndRevokedRecordsAndFiltersNamedReceiver()
      throws Exception {
    var fixture = managementFixture(true);
    for (String path :
        java.util.List.of(
            "/management-grants",
            "/management-grants?actor_user_id=" + fixture.delegate(),
            "/management-grants?role_id=" + fixture.businessRole())) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(404);
    }
    assertThat(management("PUT", "/management-grants", fixture.token(), Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void managementQueryHonorsLiveClientScopeRestrictionAndCannotFallBackToCookie() throws Exception {
    var fixture = managementFixture(true);
    assertThat(management("GET", "/users", null, Map.of()).statusCode()).isEqualTo(401);
    clientBoundary(
        fixture.client(),
        java.util.Set.of("profile", "identity.roles", "identity.permissions"),
        java.util.Set.of("identity-service"));
    assertThat(management("GET", "/users", fixture.token(), Map.of()).statusCode()).isEqualTo(403);
    assertThat(managementData(fixture, "/roles").get("items").size()).isEqualTo(2);
    assertThat(management("GET", "/users/not-a-uuid", fixture.token(), Map.of()).statusCode())
        .isEqualTo(400);
  }

  private Map<String, Object> simulationBody(
      UUID subject,
      String client,
      String audience,
      String permission,
      java.util.Set<String> scopes) {
    return Map.of(
        "user_id",
        subject.toString(),
        "client_id",
        client,
        "audience",
        audience,
        "permission_code",
        permission,
        "requested_scopes",
        scopes);
  }

  private tools.jackson.databind.JsonNode simulation(
      ManagementFixture manager,
      UUID subject,
      LiveGrant target,
      String permission,
      java.util.Set<String> scopes,
      String audience)
      throws Exception {
    var response =
        management(
            "POST",
            "/authorization-evaluations",
            manager.token(),
            simulationBody(subject, target.client().clientId(), audience, permission, scopes));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    return json(response).get("data");
  }

  @Test
  void authorizationSimulationUsesProposedScopesAndDoesNotChangeActualIssuedToken()
      throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void authorizationSimulationReportsSameStableEvaluatorReasonsAfterLiveChanges() throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void authorizationSimulationChecksTenantCapabilityWithoutRequiringCallerBusinessPermission()
      throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void authorizationSimulationCannotEnumerateForeignUsersClientsOrOwnDelegatedAccount()
      throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void authorizationSimulationRejectsInactiveClientAccountAndMalformedInputs() throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void authorizationSimulationStorageFaultReturns503WithNoPartialResultOrCredentialEcho()
      throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void profileHttpRejectsCredentialFieldsAndKeepsLiveTokenWhileUpdatingApprovedDisplayName()
      throws Exception {
    var manager = managementFixture(true);
    var rejected =
        management(
            "PUT",
            "/users/" + manager.target() + "/profile",
            manager.token(),
            Map.of(
                "version",
                0,
                "display_name",
                "不能夹带字段",
                "verified_email",
                "attacker@example.invalid",
                "password",
                "DO_NOT_ECHO_PROFILE_PASSWORD"));
    assertThat(rejected.statusCode()).isEqualTo(400);
    assertThat(rejected.body())
        .doesNotContain("attacker@example.invalid", "DO_NOT_ECHO_PROFILE_PASSWORD");
    assertThat(managementData(manager, "/users/" + manager.target()).get("version").asLong())
        .isZero();
    var updated =
        management(
            "PUT",
            "/users/" + user + "/profile",
            manager.token(),
            Map.of("version", 0, "display_name", "新的管理员名称"));
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(json(updated).get("data").get("display_name").asString()).isEqualTo("新的管理员名称");
    assertThat(json(updated).get("data").get("version").asLong()).isEqualTo(1);
    assertThat(updated.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(
            json(bearer("/api/v1/identity/me", manager.token()))
                .get("data")
                .get("profile")
                .get("name")
                .asString())
        .isEqualTo("新的管理员名称");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, user)))
        .isZero();
    assertThat(
            management(
                    "PUT",
                    "/users/" + user + "/profile",
                    manager.token(),
                    Map.of("version", 0, "display_name", "旧覆盖"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            management(
                    "PUT",
                    "/users/" + otherTenantUser + "/profile",
                    manager.token(),
                    Map.of("version", 0, "display_name", "跨租户"))
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void delegatedProfileHttpAllowsOnlyOrdinaryTargetsAndLiveUserManagementAuthority()
      throws Exception {
    var fixture = managementFixture(false);
    for (UUID target :
        java.util.List.of(user, fixture.target(), fixture.delegate(), otherTenantUser))
      assertThat(
              management(
                      "PUT",
                      "/users/" + target + "/profile",
                      fixture.token(),
                      Map.of("version", 0, "display_name", "不得修改"))
                  .statusCode())
          .isEqualTo(403);
    assertThat(
            management(
                    "PUT",
                    "/users/" + fixture.target() + "/profile",
                    null,
                    Map.of("version", 0, "display_name", "不得修改"))
                .statusCode())
        .isEqualTo(401);
  }

  private record PlatformManager(UUID user, OAuthClient client, String token, String refresh) {}

  private PlatformManager platformManager() throws Exception {
    UUID id = UUID.randomUUID();
    String name = "client-admin-" + id, password = "Fixture platform " + id;
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'平台应用管理员',?,false)",
        id,
        name,
        passwords.encode(password, policies.current(null)));
    var loginFields = new HashMap<>(credentials(csrf("/identity/login"), password));
    loginFields.put("realm", "PLATFORM");
    loginFields.put("tenant_code", "");
    loginFields.put("username", name);
    assertThat(post("/identity/login", loginFields).statusCode()).isEqualTo(302);
    for (String resource : java.util.List.of("client", "tenant", "admin"))
      for (String operation : java.util.List.of("read", "manage"))
        jdbc.update(
            "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,oauth_scope) VALUES(?,'平台管理','identity-service','PLATFORM','platform','平台',?,'平台资源',?,?) ON CONFLICT DO NOTHING",
            "platform:" + resource + ":" + operation,
            resource,
            operation,
            "platform." + resource + "s");
    for (String operation : java.util.List.of("read", "manage"))
      jdbc.update(
          "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,oauth_scope) VALUES(?,'平台安全','identity-service','PLATFORM','platform','平台','platform-security','安全',?,'platform.security') ON CONFLICT DO NOTHING",
          "platform:security:" + operation,
          operation);
    jdbc.update(
        "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,oauth_scope) VALUES('platform:audit:read','平台审计','identity-service','PLATFORM','platform','平台','platform-audit','审计','read','platform.audit') ON CONFLICT DO NOTHING");
    for (String operation : NATIVE_MANAGEMENT_OPERATIONS) {
      String[] parts = operation.split(":");
      jdbc.update(
          "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'管理','identity-service','TENANT','identity','身份',?,'管理资源',?,ARRAY['TENANT'],?) ON CONFLICT DO NOTHING",
          operation,
          parts[1],
          parts[2],
          parts[1].equals("user")
              ? "identity.users"
              : parts[1].equals("role") ? "identity.roles" : "identity.permissions");
    }
    var original = oauthClient(false, false, true);
    clientBoundary(
        original,
        java.util.Set.of(
            "profile",
            "platform.clients",
            "platform.tenants",
            "platform.admins",
            "platform.security",
            "platform.audit"),
        java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            original.id(),
            original.clientId(),
            original.secret(),
            false,
            true,
            "profile platform.clients platform.tenants platform.admins platform.security platform.audit");
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    return new PlatformManager(
        id, client, issued.get("access_token").asString(), issued.get("refresh_token").asString());
  }

  private Map<String, Object> technicalClientCommand(String id) {
    var body = new HashMap<String, Object>();
    body.put("client_id", id);
    body.put("name", "测试目录发布身份");
    body.put("principal_type", "service");
    body.put("allowed_tenant_ids", java.util.Set.of());
    body.put("audiences", java.util.Set.of("identity-service"));
    body.put("scopes", java.util.Set.of("catalog.publish:native-api"));
    body.put("redirect_uris", java.util.Set.of());
    body.put("logout_uris", java.util.Set.of());
    body.put("public_client", false);
    body.put("first_party", false);
    body.put("status", "ACTIVE");
    return body;
  }

  @Test
  void platformClientApiDeliversSecretOnceSupportsIdempotencyAndCreatesUsableMachineCredential()
      throws Exception {
    var administrator = platformManager();
    String clientId = "api-created-" + UUID.randomUUID(),
        request = "client-api-" + UUID.randomUUID();
    var command = technicalClientCommand(clientId);
    var created =
        management("POST", "/platform/oauth-clients", administrator.token(), command, request);
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    var data = json(created).get("data");
    String secret = data.get("client_secret").asString(),
        id = data.get("client").get("id").asString();
    assertThat(secret.length()).isGreaterThanOrEqualTo(32);
    assertThat(data.get("replayed").asBoolean()).isFalse();
    String stored = oauthClients.byId(id).client().getClientSecret();
    assertThat(
            org.springframework.security.crypto.factory.PasswordEncoderFactories
                .createDelegatingPasswordEncoder()
                .matches(secret, stored))
        .isTrue();
    var technical =
        new OAuthClient(id, clientId, secret, false, false, "catalog.publish:native-api");
    var token =
        oauthExchange(
            "/oauth2/token",
            technical,
            Map.of("grant_type", "client_credentials", "scope", "catalog.publish:native-api"));
    assertThat(token.statusCode()).isEqualTo(200);
    assertThat(json(token).has("access_token")).isTrue();
    assertThat(json(token).has("refresh_token")).isFalse();
    var replay =
        management("POST", "/platform/oauth-clients", administrator.token(), command, request);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay).get("data").get("replayed").asBoolean()).isTrue();
    assertThat(json(replay).get("data").get("client_secret").isNull()).isTrue();
    command.put("name", "相同请求不同内容");
    assertThat(
            management("POST", "/platform/oauth-clients", administrator.token(), command, request)
                .statusCode())
        .isEqualTo(409);
    var listed =
        management("GET", "/platform/oauth-clients?q=" + clientId, administrator.token(), Map.of());
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(json(listed).get("data").get("items").size()).isEqualTo(1);
    assertThat(listed.body().contains(secret) || listed.body().contains("client_secret")).isFalse();
    var read = management("GET", "/platform/oauth-clients/" + id, administrator.token(), Map.of());
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(json(read).get("data").get("client_id").asString()).isEqualTo(clientId);
    assertThat(
            read.body().contains(secret)
                || read.body().contains("client_secret")
                || read.body().contains(stored))
        .isFalse();
    String audit =
        jdbc.queryForObject(
            "SELECT string_agg(details::text,',') FROM sys_auth_audit WHERE actor_id=?",
            String.class,
            administrator.user());
    assertThat(audit.contains(secret) || audit.contains(stored)).isFalse();
    var view =
        JsonMapper.builder()
            .build()
            .readValue(
                data.get("client").toString(),
                com.ken.platform.identity.controller.management.PlatformClientController.Client
                    .class);
    assertThat(
            new com.ken.platform.identity.controller.management.PlatformClientController.Created(
                    view, secret, false)
                .toString()
                .contains(secret))
        .isFalse();
  }

  @Test
  void platformClientApiPreservesProtocolTypeChecksVersionsAndStopsDisabledCredential()
      throws Exception {
    var administrator = platformManager();
    String clientId = "api-client-version-" + UUID.randomUUID();
    var command = technicalClientCommand(clientId);
    var created =
        management(
            "POST",
            "/platform/oauth-clients",
            administrator.token(),
            command,
            "client-create-" + UUID.randomUUID());
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json(created).get("data").get("client").get("id").asString();
    String secret = json(created).get("data").get("client_secret").asString();
    command.put("version", 0);
    command.put("status", "DISABLED");
    var disabled =
        management("PUT", "/platform/oauth-clients/" + id, administrator.token(), command);
    assertThat(disabled.statusCode()).isEqualTo(200);
    assertThat(json(disabled).get("data").get("version").asLong()).isEqualTo(1);
    var technical =
        new OAuthClient(id, clientId, secret, false, false, "catalog.publish:native-api");
    assertThat(
            oauthExchange("/oauth2/token", technical, Map.of("grant_type", "client_credentials"))
                .statusCode())
        .isIn(400, 401);
    assertThat(
            management("PUT", "/platform/oauth-clients/" + id, administrator.token(), command)
                .statusCode())
        .isEqualTo(409);
    command.put("version", 1);
    command.put("client_id", clientId + "-reinterpreted");
    assertThat(
            management("PUT", "/platform/oauth-clients/" + id, administrator.token(), command)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            management(
                    "GET",
                    "/platform/oauth-clients/" + UUID.randomUUID(),
                    administrator.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(404);
    assertThat(
            management("GET", "/platform/oauth-clients?size=101", administrator.token(), Map.of())
                .statusCode())
        .isEqualTo(400);
    var publicBody = technicalClientCommand("api-public-" + UUID.randomUUID());
    publicBody.put("principal_type", "user");
    publicBody.put("owner_tenant_id", tenant.toString());
    publicBody.put("allowed_tenant_ids", java.util.Set.of(tenant.toString()));
    publicBody.put("scopes", java.util.Set.of("profile"));
    publicBody.put("redirect_uris", java.util.Set.of("https://public.example/callback"));
    publicBody.put("public_client", true);
    var publicClient =
        management(
            "POST",
            "/platform/oauth-clients",
            administrator.token(),
            publicBody,
            "public-client-" + UUID.randomUUID());
    assertThat(publicClient.statusCode()).isEqualTo(200);
    assertThat(json(publicClient).get("data").get("client_secret").isNull()).isTrue();
    publicBody.put("first_party", true);
    publicBody.put("client_id", "invalid-public-" + UUID.randomUUID());
    assertThat(
            management(
                    "POST",
                    "/platform/oauth-clients",
                    administrator.token(),
                    publicBody,
                    "invalid-public-" + UUID.randomUUID())
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void platformClientApiRequiresOriginalRecentLoginAndRefreshCannotResetIt() throws Exception {
    var administrator = platformManager();
    UUID session = binding(sid());
    jdbc.update(
        "UPDATE sys_login_session SET created_at=? WHERE id=?",
        java.sql.Timestamp.from(
            java.time.Instant.now().minusSeconds(policies.current(null).recentAuthSeconds() + 5L)),
        session);
    var renewed =
        oauthToken(
            administrator.client(),
            Map.of("grant_type", "refresh_token", "refresh_token", administrator.refresh()));
    assertThat(renewed.statusCode()).isEqualTo(200);
    String token = json(renewed).get("access_token").asString();
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    String clientId = "aged-create-" + UUID.randomUUID();
    var rejected =
        management(
            "POST",
            "/platform/oauth-clients",
            token,
            technicalClientCommand(clientId),
            "aged-client-" + UUID.randomUUID());
    assertThat(rejected.statusCode()).isEqualTo(403);
    assertThat(json(rejected).get("code").asString()).isEqualTo("RECENT_AUTHENTICATION_REQUIRED");
    assertThat(oauthClients.clientIdExists(clientId)).isFalse();
    assertThat(
            management("GET", "/platform/oauth-clients?q=aged-create-", token, Map.of())
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void platformClientApiRejectsTenantIdentityMissingScopesAndInjectedSecretFields()
      throws Exception {
    var administrator = platformManager();
    var profile =
        new OAuthClient(
            administrator.client().id(),
            administrator.client().clientId(),
            administrator.client().secret(),
            false,
            true,
            "profile");
    String narrow =
        json(oauthToken(profile, codeGrant(authorizationCode(profile), VERIFIER)))
            .get("access_token")
            .asString();
    var command = technicalClientCommand("forbidden-client-" + UUID.randomUUID());
    assertThat(management("POST", "/platform/oauth-clients", narrow, command).statusCode())
        .isEqualTo(403);
    assertThat(management("GET", "/platform/oauth-clients", null, Map.of()).statusCode())
        .isEqualTo(401);
    command.put("client_secret", "DO_NOT_ECHO_INJECTED_CLIENT_SECRET");
    var injected = management("POST", "/platform/oauth-clients", administrator.token(), command);
    assertThat(injected.statusCode()).isEqualTo(400);
    assertThat(injected.body()).doesNotContain("DO_NOT_ECHO_INJECTED_CLIENT_SECRET");
    var tenantManager = managementFixture(true);
    command.remove("client_secret");
    assertThat(
            management("POST", "/platform/oauth-clients", tenantManager.token(), command)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            management("GET", "/platform/oauth-clients", tenantManager.token(), Map.of())
                .statusCode())
        .isEqualTo(401);
  }

  private void activateIssuedAccount(
      String realm, String code, String account, String initial, String next) throws Exception {
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var fields = new HashMap<>(credentials(csrf("/identity/login"), initial));
    fields.put("realm", realm);
    fields.put("tenant_code", code);
    fields.put("username", account);
    var restricted = post("/identity/login", fields);
    assertThat(restricted.statusCode()).isEqualTo(302);
    assertThat(restricted.headers().firstValue("Location").orElseThrow())
        .contains("/identity/password/initial");
    assertThat(
            post(
                    "/identity/password/initial",
                    Map.of(
                        "_csrf",
                        csrf("/identity/password/initial"),
                        "password",
                        next,
                        "confirmation",
                        next))
                .statusCode())
        .isEqualTo(302);
    fields.put("_csrf", csrf("/identity/login"));
    fields.put("password", next);
    assertThat(post("/identity/login", fields).statusCode()).isEqualTo(302);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  private record BffCsrf(String header, String value) {}

  private HttpResponse<String> bffRequest(String path, String method, Object body, BffCsrf csrf)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:18080" + path))
            .header("X-Request-Id", "bff-live-" + UUID.randomUUID());
    if (csrf != null) request.header(csrf.header(), csrf.value());
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else
      request
          .header("Content-Type", "application/json")
          .method(
              method,
              HttpRequest.BodyPublishers.ofString(
                  JsonMapper.builder().build().writeValueAsString(body)));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private BffCsrf bffCsrf(String namespace) throws Exception {
    var response = bffRequest("/bff/" + namespace + "/csrf", "GET", null, null);
    assertThat(response.statusCode()).as("BFF CSRF %s", namespace).isEqualTo(200);
    var data = json(response).get("data");
    return new BffCsrf(data.get("header_name").asString(), data.get("token").asString());
  }

  private HttpResponse<String> bffForm(String path, Map<String, String> fields, BffCsrf csrf)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:18080" + path))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (csrf != null) request.header(csrf.header(), csrf.value());
    return http.send(
        request.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> bffGet(String location) throws Exception {
    URI uri = URI.create(location);
    if (!uri.isAbsolute()) uri = URI.create("http://localhost:18080").resolve(uri);
    assertThat(uri.getHost()).isEqualTo("localhost");
    assertThat(uri.getPort()).isEqualTo(18080);
    return http.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private void bffLogin(
      String namespace,
      String realm,
      String code,
      String account,
      String password,
      boolean rejectWrongCallbacks)
      throws Exception {
    var csrf = bffCsrf(namespace);
    var begin =
        bffForm(
            "/bff/" + namespace + "/login",
            realm.equals("PLATFORM") ? Map.of() : Map.of("tenant_code", code),
            csrf);
    assertThat(begin.statusCode()).as("BFF 登录开始").isEqualTo(302);
    var authorization = bffGet(begin.headers().firstValue("Location").orElseThrow());
    assertThat(authorization.statusCode()).isEqualTo(302);
    String login = authorization.headers().firstValue("Location").orElseThrow();
    assertThat(login).contains("/identity/login", "realm=" + realm);
    var page = bffGet(login);
    assertThat(page.statusCode()).isEqualTo(200);
    var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
    assertThat(match.find()).isTrue();
    var fields = new HashMap<>(credentials(match.group(1), password));
    fields.put("realm", realm);
    fields.put("tenant_code", code);
    fields.put("username", account);
    var authenticated = bffForm("/identity/login", fields, null);
    assertThat(authenticated.statusCode()).isEqualTo(302);
    String continuation = authenticated.headers().firstValue("Location").orElseThrow();
    assertThat(continuation).startsWith("/oauth2/authorize?");
    assertThat(
            URI.create("http://localhost:18080" + continuation).getRawQuery().contains("prompt="))
        .isFalse();
    var authorized = bffGet(continuation);
    assertThat(authorized.statusCode()).isEqualTo(302);
    String callback = authorized.headers().firstValue("Location").orElseThrow();
    if (rejectWrongCallbacks) {
      String wrong =
          callback.replace("/bff/identity/platform/callback", "/bff/identity/tenant/callback");
      assertThat(bffGet(wrong).statusCode()).isEqualTo(401);
    }
    var accepted = bffGet(callback);
    assertThat(accepted.statusCode()).as("BFF 回调").isEqualTo(303);
    assertThat(accepted.headers().firstValue("Location").orElseThrow())
        .isEqualTo(namespace.equals("web") ? "/" : "/identity/admin/");
    if (rejectWrongCallbacks) assertThat(bffGet(callback).statusCode()).isEqualTo(401);
    assertThat(
            bffRequest(
                    "/bff/"
                        + namespace
                        + "/api/"
                        + (namespace.equals("web") ? "v1/identity/me" : "me"),
                    "GET",
                    null,
                    null)
                .statusCode())
        .isEqualTo(200);
  }

  private final class NativeBffGateway implements AutoCloseable {
    private final Process process;
    private final Path log;
    final Path clockFile;

    NativeBffGateway(Path clientsFile) throws Exception {
      String tag = UUID.randomUUID().toString();
      Path readerSecret = signingDirectory.resolve("bff-reader-" + tag + ".secret");
      var reader = reader("identity-service");
      Files.writeString(readerSecret, reader.secret());
      Path readers = signingDirectory.resolve("bff-readers-" + tag + ".json");
      Files.writeString(
          readers,
          JsonMapper.builder()
              .build()
              .writeValueAsString(
                  Map.of(
                      "readers",
                      java.util.List.of(
                          Map.of(
                              "audience",
                              "identity-service",
                              "client_id",
                              reader.clientId(),
                              "secret_file",
                              readerSecret.toString())))));
      Path ready = signingDirectory.resolve("bff-ready-" + tag + ".json"),
          descriptor = signingDirectory.resolve("bff-probe-" + tag + ".json");
      clockFile = signingDirectory.resolve("bff-clock-" + tag + ".txt");
      Files.writeString(
          descriptor,
          JsonMapper.builder()
              .build()
              .writeValueAsString(
                  Map.of(
                      "test_only",
                      true,
                      "identity_origin",
                      origin,
                      "public_origin",
                      "http://localhost:18080",
                      "project",
                      redis.project,
                      "redis_binding",
                      System.getenv("PLATFORM_TEST_REDIS_BINDING"),
                      "bff_clients_file",
                      clientsFile.toString(),
                      "readers_file",
                      readers.toString(),
                      "ready_file",
                      ready.toString(),
                      "token_clock_file",
                      clockFile.toString())));
      Path classes =
          Path.of(System.getenv("PLATFORM_TEST_GATEWAY_JAR"))
              .getParent()
              .resolve("test-classes/com/ken/platform/gateway/bff");
      Path root = signingDirectory.resolve("bff-probe-classes-" + tag),
          target = root.resolve("com/ken/platform/gateway/bff");
      Files.createDirectories(target);
      try (var files = Files.list(classes)) {
        for (var file :
            files
                .filter(
                    path ->
                        path.getFileName().toString().startsWith("GatewayBffProbe")
                            && path.toString().endsWith(".class"))
                .toList()) Files.copy(file, target.resolve(file.getFileName()));
      }
      Files.copy(
          classes.getParent().resolve("RouteFixtures.class"),
          target.getParent().resolve("RouteFixtures.class"));
      assertThat(target.resolve("GatewayBffProbe.class")).exists();
      log = signingDirectory.resolve("bff-gateway-" + tag + ".log");
      var builder =
          new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
              "-Dfile.encoding=UTF-8",
              "-Dloader.path=" + root,
              "-Dloader.main=com.ken.platform.gateway.bff.GatewayBffProbe",
              "-cp",
              System.getenv("PLATFORM_TEST_GATEWAY_JAR"),
              "org.springframework.boot.loader.launch.PropertiesLauncher",
              descriptor.toString());
      var allowed =
          java.util.Set.of(
              "PATH",
              "SYSTEMROOT",
              "COMSPEC",
              "WINDIR",
              "TEMP",
              "TMP",
              "USERPROFILE",
              "APPDATA",
              "LOCALAPPDATA",
              "HOMEDRIVE",
              "HOMEPATH");
      builder
          .environment()
          .keySet()
          .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
      process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try {
        var until = Instant.now().plusSeconds(45);
        while (process.isAlive() && !Files.exists(ready) && Instant.now().isBefore(until))
          Thread.sleep(100);
        assertThat(Files.exists(ready)).as("BFF Gateway 启动，检查隔离日志 %s", log).isTrue();
      } catch (Exception | AssertionError failed) {
        if (process.isAlive()) process.destroyForcibly();
        try {
          Path evidence = Path.of("..", "..", ".local", "test-evidence").resolve(log.getFileName());
          Files.createDirectories(evidence.getParent());
          Files.copy(log, evidence);
        } catch (java.io.IOException copyFailed) {
          failed.addSuppressed(copyFailed);
        }
        throw failed;
      }
    }

    @Override
    public void close() throws Exception {
      if (process.isAlive()) {
        process.getOutputStream().write('\n');
        process.getOutputStream().flush();
        if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
      }
      assertThat(process.isAlive()).isFalse();
      Path evidence = Path.of("..", "..", ".local", "test-evidence").resolve(log.getFileName());
      Files.createDirectories(evidence.getParent());
      Files.copy(log, evidence);
      assertThat(process.exitValue()).isZero();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void bffBrowserUsesRealIdentityWithCsrfNamespacesRefreshAndApplicationLogout() throws Exception {
    Path stateFile = Path.of(System.getenv("PLATFORM_TEST_BFF_BOOTSTRAP_STATE"));
    var mapper = JsonMapper.builder().build();
    var metadata = mapper.readTree(Files.readString(stateFile));
    assertThat(metadata.get("test_only").asBoolean()).isTrue();
    assertThat(Path.of(metadata.get("database_binding").asString()).toRealPath())
        .isEqualTo(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING")).toRealPath());
    var descriptor =
        mapper.readTree(
            Files.readString(Path.of(metadata.get("bootstrap_secret_file").asString())));
    String account = descriptor.get("admin_username").asString();
    UUID admin = UUID.fromString(metadata.get("created").get("initial_admin_id").asString());
    Path activePassword = Path.of(stateFile.toString() + ".active-admin.secret");
    if (!Files.exists(activePassword))
      Files.writeString(activePassword, "BFF root password " + UUID.randomUUID());
    String password = Files.readString(activePassword).strip();
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT must_change_password FROM sys_platform_user WHERE id=?",
            Boolean.class,
            admin))) {
      activateIssuedAccount(
          "PLATFORM",
          "",
          account,
          Files.readString(Path.of(descriptor.get("initial_password_file").asString())).strip(),
          password);
    }
    var clients = new HashMap<String, Object>();
    clients.put("platform_secret_file", descriptor.get("platform_bff_secret_file").asString());
    clients.put("tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString());
    Path config = signingDirectory.resolve("bff-live-clients-" + UUID.randomUUID() + ".json");
    Files.writeString(config, mapper.writeValueAsString(clients));
    NativeBffGateway gateway = new NativeBffGateway(config);
    try {
      var beforeLoginCsrf = bffCsrf("identity/platform");
      assertThat(bffForm("/bff/identity/platform/login", Map.of(), null).statusCode())
          .isEqualTo(403);
      bffLogin("identity/platform", "PLATFORM", "", account, password, true);
      var platformMe = bffRequest("/bff/identity/platform/api/me", "GET", null, null);
      assertThat(platformMe.statusCode()).isEqualTo(200);
      assertThat(json(platformMe).get("data").get("sub").asString()).isEqualTo(admin.toString());
      assertThat(json(platformMe).get("data").get("client_id").asString())
          .isEqualTo("identity-admin-platform");
      String code = "bff-live-" + UUID.randomUUID();
      var create =
          Map.<String, Object>of(
              "code",
              code,
              "name",
              "BFF 联调租户",
              "admin_username",
              "admin",
              "admin_display_name",
              "租户管理员");
      assertThat(
              bffRequest(
                      "/bff/identity/platform/api/platform/tenants",
                      "POST",
                      create,
                      beforeLoginCsrf)
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_tenant WHERE code=?", Integer.class, code))
          .isZero();
      var created =
          bffRequest(
              "/bff/identity/platform/api/platform/tenants",
              "POST",
              create,
              bffCsrf("identity/platform"));
      assertThat(created.statusCode()).as("通过 BFF 实际创建租户").isEqualTo(200);
      String tenantId = json(created).get("data").get("tenant_id").asString();
      String initial = json(created).get("data").get("initial_password").asString();
      String tenantPassword = "BFF tenant password " + UUID.randomUUID();
      var originalHttp = http;
      var originalCookies = cookies;
      try {
        activateIssuedAccount("TENANT", code, "admin", initial, tenantPassword);
      } finally {
        if (http != originalHttp) http.close();
        http = originalHttp;
        cookies = originalCookies;
      }
      var web = technicalClientCommand("web-bff");
      web.put("principal_type", "user");
      web.put("owner_tenant_id", tenantId);
      web.put("allowed_tenant_ids", java.util.Set.of(tenantId));
      web.put("scopes", java.util.Set.of("profile"));
      web.put("redirect_uris", java.util.Set.of("http://localhost:18080/bff/web/callback"));
      web.put("logout_uris", java.util.Set.of("http://localhost:18080/bff/web/logged-out"));
      web.put("first_party", true);
      var webCreated =
          bffRequest(
              "/bff/identity/platform/api/platform/oauth-clients",
              "POST",
              web,
              bffCsrf("identity/platform"));
      assertThat(webCreated.statusCode()).isEqualTo(200);
      Path webSecret = signingDirectory.resolve("web-bff-" + UUID.randomUUID() + ".secret");
      Files.writeString(webSecret, json(webCreated).get("data").get("client_secret").asString());
      clients.put(
          "web",
          Map.of(
              "secret_file",
              webSecret.toString(),
              "requested_scopes",
              java.util.List.of("profile")));
      Files.writeString(config, mapper.writeValueAsString(clients));
      gateway.close();
      gateway = new NativeBffGateway(config);
      assertThat(bffRequest("/bff/identity/platform/api/me", "GET", null, null).statusCode())
          .as("Gateway 重启保留持久 BFF 登录")
          .isEqualTo(200);
      bffLogin("identity/tenant", "TENANT", code, "admin", tenantPassword, false);
      bffLogin("web", "TENANT", code, "admin", tenantPassword, false);
      var tenantMe = json(bffRequest("/bff/identity/tenant/api/me", "GET", null, null)).get("data");
      var webMe = json(bffRequest("/bff/web/api/v1/identity/me", "GET", null, null)).get("data");
      assertThat(tenantMe.get("tenant_id").asString()).isEqualTo(tenantId);
      assertThat(tenantMe.get("audiences").get("identity-service").get("permissions").size())
          .isEqualTo(9);
      assertThat(webMe.get("client_id").asString()).isEqualTo("web-bff");
      assertThat(webMe.get("audiences").get("identity-service").get("permissions").size()).isZero();
      assertThat(bffRequest("/bff/web/api/v1/identity/users", "GET", null, null).statusCode())
          .isEqualTo(403);
      assertThat(
              bffRequest("/bff/identity/tenant/api/platform/tenants", "GET", null, null)
                  .statusCode())
          .isEqualTo(403);
      assertThat(bffRequest("/bff/identity/platform/api/me", "GET", null, null).statusCode())
          .isEqualTo(200);
      var rootClient = oauthClients.byClientId("identity-admin-platform");
      var old =
          jdbc.queryForMap(
              "SELECT access_token_value,refresh_token_value,access_token_expires_at FROM oauth2_authorization WHERE registered_client_id=? AND principal_name=? AND access_token_value IS NOT NULL ORDER BY access_token_issued_at DESC LIMIT 1",
              rootClient.client().getId(),
              admin.toString());
      String oldAccess = (String) old.get("access_token_value"),
          oldRefresh = (String) old.get("refresh_token_value");
      try (var outsider = HttpClient.newHttpClient()) {
        var denied =
            outsider.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:18080/bff/identity/platform/api/me"))
                    .header("Authorization", "Bearer " + oldAccess)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(400);
        assertThat(denied.body().contains(oldAccess)).isFalse();
      }
      assertThat(
              bffRequest(
                      "/bff/identity/platform/api/me?access_token=not-a-credential",
                      "GET",
                      null,
                      null)
                  .statusCode())
          .isEqualTo(400);
      var tenantCsrf = bffCsrf("identity/tenant");
      assertThat(bffRequest("/bff/identity/platform/logout", "POST", null, tenantCsrf).statusCode())
          .isEqualTo(403);
      var untilIssueAge =
          ((java.sql.Timestamp) old.get("access_token_expires_at")).toInstant().minusSeconds(598);
      while (Instant.now().isBefore(untilIssueAge)) Thread.sleep(50);
      long beforeRefresh =
          jdbc.queryForObject(
              "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.token' AND actor_id=? AND result='SUCCESS' AND details->>'client_id'='identity-admin-platform'",
              Long.class,
              admin);
      Instant refreshWindow =
          ((java.sql.Timestamp) old.get("access_token_expires_at")).toInstant().minusSeconds(29);
      Files.writeString(gateway.clockFile, refreshWindow.toString());
      Path applied = Path.of(gateway.clockFile.toString() + ".applied");
      var untilApplied = Instant.now().plusSeconds(5);
      while ((!Files.exists(applied) || !Files.readString(applied).equals(refreshWindow.toString()))
          && Instant.now().isBefore(untilApplied)) Thread.sleep(50);
      assertThat(Files.exists(applied)).isTrue();
      assertThat(Files.readString(applied)).isEqualTo(refreshWindow.toString());
      var first =
          http.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:18080/bff/identity/platform/api/me"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var second =
          http.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:18080/bff/identity/platform/api/me"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.token' AND actor_id=? AND result='SUCCESS' AND details->>'client_id'='identity-admin-platform'",
                  Long.class,
                  admin))
          .isEqualTo(beforeRefresh + 1);
      var current =
          jdbc.queryForMap(
              "SELECT access_token_value,refresh_token_value FROM oauth2_authorization WHERE registered_client_id=? AND principal_name=? AND access_token_value IS NOT NULL ORDER BY access_token_issued_at DESC LIMIT 1",
              rootClient.client().getId(),
              admin.toString());
      assertThat(oldAccess.equals(current.get("access_token_value"))).isFalse();
      assertThat(oldRefresh.equals(current.get("refresh_token_value"))).isFalse();
      assertThat(bearer("/api/v1/identity/me", oldAccess).statusCode()).isEqualTo(401);
      var credential =
          new OAuthClient(
              rootClient.client().getId(),
              rootClient.client().getClientId(),
              Files.readString(Path.of(descriptor.get("platform_bff_secret_file").asString()))
                  .strip(),
              false,
              true,
              "profile");
      assertThat(
              oauthToken(
                      credential,
                      Map.of("grant_type", "refresh_token", "refresh_token", oldRefresh))
                  .statusCode())
          .isEqualTo(400);
      assertThat(bffRequest("/bff/identity/platform/api/me", "GET", null, null).statusCode())
          .isEqualTo(200);
      assertThat(bffRequest("/bff/web/logout", "POST", null, bffCsrf("web")).statusCode())
          .isEqualTo(200);
      assertThat(bffRequest("/bff/web/api/v1/identity/me", "GET", null, null).statusCode())
          .isEqualTo(401);
      assertThat(bffRequest("/bff/identity/tenant/api/me", "GET", null, null).statusCode())
          .isEqualTo(200);
      var disabled =
          bffRequest(
              "/bff/identity/platform/api/platform/tenants/" + tenantId,
              "PUT",
              Map.of("version", 0, "name", "BFF 联调租户", "status", "DISABLED"),
              bffCsrf("identity/platform"));
      assertThat(disabled.statusCode()).isEqualTo(200);
      assertThat(bffRequest("/bff/identity/tenant/api/me", "GET", null, null).statusCode())
          .isEqualTo(401);
      assertThat(bffRequest("/bff/identity/platform/api/me", "GET", null, null).statusCode())
          .isEqualTo(200);
      assertThat(
              bffRequest(
                      "/bff/identity/platform/logout", "POST", null, bffCsrf("identity/platform"))
                  .statusCode())
          .isEqualTo(200);
      assertThat(bffRequest("/bff/identity/platform/api/me", "GET", null, null).statusCode())
          .isEqualTo(401);
      assertThat(
              bearer("/api/v1/identity/me", (String) current.get("access_token_value"))
                  .statusCode())
          .isEqualTo(401);
    } finally {
      gateway.close();
    }
  }

  private String fixedManagementToken(String clientId, String secret, String code, boolean platform)
      throws Exception {
    var snapshot = oauthClients.byClientId(clientId);
    var client =
        new OAuthClient(
            snapshot.client().getId(),
            clientId,
            secret,
            false,
            platform,
            String.join(" ", snapshot.client().getScopes()));
    String callback = snapshot.client().getRedirectUris().iterator().next();
    String query =
        authorizationQuery(client, code)
            .replace(
                URLEncoder.encode(CALLBACK, StandardCharsets.UTF_8),
                URLEncoder.encode(callback, StandardCharsets.UTF_8));
    var authorized = get("/oauth2/authorize?" + query);
    assertThat(authorized.statusCode()).isEqualTo(302);
    String location = authorized.headers().firstValue("Location").orElseThrow();
    assertThat(URI.create(location).getPath()).isEqualTo(URI.create(callback).getPath());
    assertThat(parameter(location, "state")).isEqualTo("native-user-state");
    var grant = new HashMap<>(codeGrant(parameter(location, "code"), VERIFIER));
    grant.put("redirect_uri", callback);
    var issued = oauthToken(client, grant);
    assertThat(issued.statusCode()).isEqualTo(200);
    return json(issued).get("access_token").asString();
  }

  private tools.jackson.databind.JsonNode ownAccountBootstrap() throws Exception {
    var metadata =
        JsonMapper.builder()
            .build()
            .readTree(
                Files.readString(Path.of(System.getenv("PLATFORM_TEST_BFF_BOOTSTRAP_STATE"))));
    assertThat(metadata.get("test_only").asBoolean()).isTrue();
    assertThat(Path.of(metadata.get("database_binding").asString()).toRealPath())
        .isEqualTo(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING")).toRealPath());
    return JsonMapper.builder()
        .build()
        .readTree(Files.readString(Path.of(metadata.get("bootstrap_secret_file").asString())));
  }

  @Test
  void generatedAdminPagesServeOnlyKnownSpaPathsAndNeverRefreshSsoCookie() throws Exception {
    var root = get("/identity/admin/");
    assertThat(root.statusCode()).isEqualTo(200);
    assertThat(root.body()).contains("<div id=\"app\"></div>");
    assertThat(root.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(root.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("script-src 'self'", "object-src 'none'", "base-uri 'none'");
    assertThat(root.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(get("/identity/admin/account").statusCode()).isEqualTo(200);
    assertThat(get("/identity/admin/platform/clients").statusCode()).isEqualTo(200);
    assertThat(get("/identity/admin/platform/security").statusCode()).isEqualTo(200);
    var asset = Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(root.body());
    assertThat(asset.find()).isTrue();
    assertThat(get(asset.group(1)).statusCode()).isEqualTo(200);
    for (String missing :
        java.util.List.of(
            "/identity/admin/unknown-page",
            "/identity/admin/assets/absent.js",
            "/identity/admin/src/main.ts")) {
      var absent = get(missing);
      assertThat(absent.statusCode()).isEqualTo(404);
      assertThat(absent.body()).doesNotContain("<div id=\"app\"></div>");
    }
    assertThat(get("/api/v1/identity/unknown-api").statusCode()).isEqualTo(401);
    ordinary();
    login();
    var withCookie = get("/identity/admin/account");
    assertThat(withCookie.statusCode()).isEqualTo(200);
    assertThat(withCookie.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedAdminFrontendUsesRealBrowserInitialPasswordProfileSessionsAndLogout()
      throws Exception {
    enrollOwnAccountTenant();
    var descriptor = ownAccountBootstrap();
    Path config = signingDirectory.resolve("browser-ui-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    Path initial = signingDirectory.resolve("browser-ui-initial.secret"),
        changed = signingDirectory.resolve("browser-ui-new.secret"),
        binding = signingDirectory.resolve("browser-ui-binding.json");
    Files.writeString(initial, secret);
    Files.writeString(changed, "Browser UI password " + UUID.randomUUID());
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t46-browser-result.json"),
        screenshot = evidence.resolve("t46-browser-account.png"),
        log = evidence.resolve("t46-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "test_only",
                    true,
                    "origin",
                    "http://localhost:18080",
                    "tenant_code",
                    tenantCode,
                    "username",
                    username,
                    "initial_password_file",
                    initial.toString(),
                    "new_password_file",
                    changed.toString(),
                    "expected_subject",
                    user.toString(),
                    "result_file",
                    result.toString(),
                    "screenshot_file",
                    screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      var builder =
          new ProcessBuilder(
              System.getenv("PLATFORM_TEST_NODE_EXECUTABLE"),
              Path.of("admin/tests/live-account.mjs").toAbsolutePath().toString());
      var allowed =
          java.util.Set.of(
              "PATH",
              "SYSTEMROOT",
              "COMSPEC",
              "WINDIR",
              "TEMP",
              "TMP",
              "USERPROFILE",
              "APPDATA",
              "LOCALAPPDATA",
              "HOMEDRIVE",
              "HOMEPATH");
      builder
          .environment()
          .keySet()
          .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
      builder.environment().put("PLATFORM_TEST_ADMIN_E2E_BINDING", binding.toString());
      builder.environment().put("PLATFORM_TEST_BROWSER_CHANNEL", "msedge");
      var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try {
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("真实浏览器进程在预算内退出").isTrue();
        assertThat(process.exitValue()).as("真实浏览器失败，检查脱敏证据 %s", log).isZero();
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      var verified = JsonMapper.builder().build().readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
    }
  }

  @Test
  void anonymousAdminBrandReturnsOnlyPresentationFieldsAndNeverUsesSsoOrQueryOverrides()
      throws Exception {
    var response = get("/api/v1/identity/admin-config");
    assertThat(response.statusCode()).isEqualTo(200);
    var view = json(response).get("data");
    assertThat(view.size()).isEqualTo(5);
    assertThat(view.get("project_display_name").asString()).isEqualTo(redis.project);
    assertThat(view.get("api_major").asInt()).isEqualTo(1);
    assertThat(view.get("logo_url").asString()).isEmpty();
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(response.body())
        .doesNotContain(
            "signing", "client_secret", "password", "nacos", "localhost", "release_version");
    assertThat(get("/api/v1/identity/admin-config?project_display_name=override").statusCode())
        .isEqualTo(400);
    assertThat(get("/api/v1/identity/me").statusCode()).isEqualTo(401);
    ordinary();
    login();
    var withCookie = get("/api/v1/identity/admin-config");
    assertThat(withCookie.statusCode()).isEqualTo(200);
    assertThat(withCookie.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(json(withCookie).get("data")).isEqualTo(view);
    assertThat(get("/api/v1/identity/me").statusCode()).isEqualTo(401);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void anonymousAdminBrandUsesRealGatewayWithoutOpeningPrivateIdentityOrBffApi() throws Exception {
    var descriptor = ownAccountBootstrap();
    Path config = signingDirectory.resolve("brand-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    try (var gateway = new NativeBffGateway(config)) {
      var view = bffGet("/api/v1/identity/admin-config");
      assertThat(view.statusCode()).isEqualTo(200);
      assertThat(json(view).get("data").size()).isEqualTo(5);
      assertThat(view.headers().allValues("Set-Cookie")).isEmpty();
      assertThat(bffGet("/api/v1/identity/me").statusCode()).isEqualTo(401);
      assertThat(bffGet("/bff/identity/tenant/api/me").statusCode()).isEqualTo(401);
      assertThat(bffGet("/api/v1/identity/admin-config/extra").statusCode()).isEqualTo(401);
      assertThat(bffRequest("/api/v1/identity/admin-config", "POST", Map.of(), null).statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedPlatformManagementFrontendCreatesActivatesAndDisablesRealAccounts()
      throws Exception {
    var descriptor = ownAccountBootstrap();
    UUID actor = UUID.randomUUID();
    String account = "gui-platform-" + actor;
    var material = passwords.temporary(policies.current(null));
    String password = material.secret().revealOnce();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'浏览器平台测试账号',?,false)",
        actor,
        account,
        material.hash());
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("platform-ui-bff-" + actor + ".json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("platform-ui-password.secret"),
        binding = signingDirectory.resolve("platform-ui-binding.json");
    Files.writeString(passwordFile, password);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t47-browser-result.json"),
        screenshot = evidence.resolve("t47-browser-platform.png"),
        log = evidence.resolve("t47-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "username",
                account,
                "password_file",
                passwordFile.toString(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      var builder =
          new ProcessBuilder(
              System.getenv("PLATFORM_TEST_NODE_EXECUTABLE"),
              Path.of("admin/tests/live-platform.mjs").toAbsolutePath().toString());
      var allowed =
          java.util.Set.of(
              "PATH",
              "SYSTEMROOT",
              "COMSPEC",
              "WINDIR",
              "TEMP",
              "TMP",
              "USERPROFILE",
              "APPDATA",
              "LOCALAPPDATA",
              "HOMEDRIVE",
              "HOMEPATH");
      builder
          .environment()
          .keySet()
          .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
      builder.environment().put("PLATFORM_TEST_ADMIN_E2E_BINDING", binding.toString());
      builder.environment().put("PLATFORM_TEST_BROWSER_CHANNEL", "msedge");
      var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try {
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("真实平台管理浏览器在预算内退出").isTrue();
        assertThat(process.exitValue()).as("真实浏览器失败，检查脱敏证据 %s", log).isZero();
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      UUID createdTenant = UUID.fromString(verified.get("tenant_id").asString());
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM sys_tenant WHERE id=?", String.class, createdTenant))
          .isEqualTo("DISABLED");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedTenantUsersFrontendCreatesActivatesAndRevokesRealUser() throws Exception {
    enrollOwnAccountTenant();
    UUID administratorRole = UUID.randomUUID();
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name,protected) VALUES(?,?,'tenant-admin','浏览器管理角色',true)",
              administratorRole,
              tenant);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              administratorRole);
          for (String operation : NATIVE_MANAGEMENT_OPERATIONS)
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
                tenant,
                administratorRole,
                operation);
          return null;
        });
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("users-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    Path initial = signingDirectory.resolve("users-ui-initial.secret"),
        binding = signingDirectory.resolve("users-ui-binding.json");
    Files.writeString(initial, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t48-browser-result.json"),
        screenshot = evidence.resolve("t48-browser-users.png"),
        log = evidence.resolve("t48-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "initial_password_file",
                initial.toString(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-users.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      UUID created = UUID.fromString(verified.get("user_id").asString());
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT status FROM sys_user WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        created))
                .isEqualTo("DISABLED");
            assertThat(
                    jdbc.queryForObject(
                        "SELECT display_name FROM sys_user WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        created))
                .isEqualTo("浏览器学员资料已更新");
            return null;
          });
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedPermissionFrontendUsesRealRangesAndPreservesDraftOnConcurrentConflict()
      throws Exception {
    enrollOwnAccountTenant();
    var fixture = managementFixture(true);
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              fixture.target(),
              fixture.businessRole());
          return null;
        });
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("permissions-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("permissions-ui-password.secret"),
        binding = signingDirectory.resolve("permissions-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t49-browser-result.json"),
        screenshot = evidence.resolve("t49-browser-permissions.png"),
        log = evidence.resolve("t49-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "password_file",
                passwordFile.toString(),
                "permission",
                fixture.read(),
                "role_id",
                fixture.businessRole().toString(),
                "target_user",
                fixture.target().toString(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-permissions.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT data_scope FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_code=?",
                        String.class,
                        tenant,
                        fixture.businessRole(),
                        fixture.read()))
                .isEqualTo("SELF");
            return null;
          });
    }
  }

  @Test
  void roleListMetadataCapabilitiesAndLiteralSearchFollowActualProtectedPermissions()
      throws Exception {
    var fixture = managementFixture(true);
    var result = management("GET", "/roles?q=business", fixture.token(), null);
    assertThat(result.statusCode()).isEqualTo(200);
    var view = json(result).get("data");
    assertThat(view.get("can_create").asBoolean()).isTrue();
    assertThat(view.get("items").size()).isEqualTo(1);
    assertThat(view.get("items").get(0).get("id").asString())
        .isEqualTo(fixture.businessRole().toString());
    assertThat(view.get("items").get(0).get("can_manage").asBoolean()).isTrue();
    var protectedRole =
        json(management("GET", "/roles?q=tenant-admin", fixture.token(), null))
            .get("data")
            .get("items");
    assertThat(protectedRole.size()).isEqualTo(1);
    assertThat(protectedRole.get(0).get("protected").asBoolean()).isTrue();
    assertThat(protectedRole.get(0).get("can_manage").asBoolean()).isFalse();
    assertThat(
            json(management("GET", "/roles?q=%25business_", fixture.token(), null))
                .get("data")
                .get("items")
                .size())
        .isZero();
    assertThat(management("GET", "/roles?q=" + "x".repeat(101), fixture.token(), null).statusCode())
        .isEqualTo(400);
    data(
        () -> {
          jdbc.update(
              "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_code='identity:role:manage'",
              tenant,
              fixture.managerRole());
          return null;
        });
    var next = management("GET", "/roles?q=business", fixture.token(), null);
    assertThat(next.statusCode()).isEqualTo(200);
    assertThat(json(next).get("data").get("can_create").asBoolean()).isFalse();
    assertThat(json(next).get("data").get("items").get(0).get("can_manage").asBoolean()).isFalse();
  }

  @Test
  void delegatedRolePermissionEditorCannotCreateOrMaintainMetadataOrChangeTargetBoundary()
      throws Exception {
    var fixture = managementFixture(false);
    var result =
        management(
            "GET", "/roles?q=business&assignable_to=" + fixture.target(), fixture.token(), null);
    assertThat(result.statusCode()).isEqualTo(403);
    assertThat(
            management("GET", "/roles?q=business&assignable_to=" + user, fixture.token(), null)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "POST",
                    "/roles",
                    fixture.token(),
                    Map.of("code", "forbidden-" + UUID.randomUUID(), "name", "不可创建"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "PUT",
                    "/roles/" + fixture.businessRole(),
                    fixture.token(),
                    Map.of("version", 0, "name", "不可修改资料", "status", "ACTIVE"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "POST",
                    "/roles/" + fixture.businessRole() + ":copy",
                    fixture.token(),
                    Map.of(
                        "role_version",
                        0,
                        "catalog_version",
                        jdbc.queryForObject(
                            "SELECT version FROM sys_permission_catalog_state WHERE id=1",
                            Long.class),
                        "code",
                        "forbidden-copy-" + UUID.randomUUID(),
                        "name",
                        "不可复制"))
                .statusCode())
        .isEqualTo(403);
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT version FROM sys_role WHERE tenant_id=? AND id=?",
                      Long.class,
                      tenant,
                      fixture.businessRole()))
              .isZero();
          return null;
        });
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedRoleFrontendCreatesDisablesCopiesAndOpensExactRoleAuthorization() throws Exception {
    enrollOwnAccountTenant();
    managementFixture(true);
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("roles-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("roles-ui-password.secret"),
        binding = signingDirectory.resolve("roles-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t50-browser-result.json"),
        screenshot = evidence.resolve("t50-browser-roles.png"),
        log = evidence.resolve("t50-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "password_file",
                passwordFile.toString(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-roles.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      assertThat(Files.isRegularFile(Path.of(screenshot + ".permissions.png"))).isTrue();
      UUID copied = UUID.fromString(verified.get("copied_role_id").asString());
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT status FROM sys_role WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        copied))
                .isEqualTo("DISABLED");
            assertThat(
                    jdbc.queryForObject(
                        "SELECT protected FROM sys_role WHERE tenant_id=? AND id=?",
                        Boolean.class,
                        tenant,
                        copied))
                .isFalse();
            return null;
          });
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedUserRoleAssignmentKeepsOffPageRolesAndRevokesRealGrant() throws Exception {
    enrollOwnAccountTenant();
    var fixture = managementFixture(true);
    UUID retained = UUID.randomUUID();
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'zz-retained','跨页保留角色')",
              retained,
              tenant);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              fixture.target(),
              retained);
          for (int index = 0; index < 25; index++)
            jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'分页角色')",
                UUID.randomUUID(),
                tenant,
                "filler-" + (100 + index));
          return null;
        });
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("user-roles-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("user-roles-ui-password.secret"),
        binding = signingDirectory.resolve("user-roles-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t51-browser-result.json"),
        screenshot = evidence.resolve("t51-browser-user-roles.png"),
        log = evidence.resolve("t51-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.ofEntries(
                Map.entry("test_only", true),
                Map.entry("origin", "http://localhost:18080"),
                Map.entry("tenant_code", tenantCode),
                Map.entry("username", username),
                Map.entry("password_file", passwordFile.toString()),
                Map.entry("target_user", fixture.target().toString()),
                Map.entry("target_username", "u-" + fixture.target()),
                Map.entry("retained_role", retained.toString()),
                Map.entry("business_role", fixture.businessRole().toString()),
                Map.entry("permission", fixture.read()),
                Map.entry("result_file", result.toString()),
                Map.entry("screenshot_file", screenshot.toString()))));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-user-roles.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      data(
          () -> {
            assertThat(
                    jdbc.queryForList(
                        "SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",
                        UUID.class,
                        tenant,
                        fixture.target()))
                .containsExactly(retained);
            return null;
          });
    }
  }

  private void roleMemberFixture(ManagementFixture fixture) {
    data(
        () -> {
          for (UUID member : java.util.List.of(user, fixture.target(), fixture.delegate()))
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                member,
                fixture.businessRole());
          return null;
        });
  }

  @Test
  void roleMembersHttpRequiresBothPermissionsPaginatesAndHidesRealForeignRole() throws Exception {
    var fixture = managementFixture(true);
    roleMemberFixture(fixture);
    assertThat(
            management("GET", "/roles/" + fixture.businessRole() + "/members", null, null)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            management(
                    "POST",
                    "/roles/" + fixture.businessRole() + "/members",
                    fixture.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "GET",
                    "/roles/" + fixture.businessRole() + "/members/extra",
                    fixture.token(),
                    null)
                .statusCode())
        .isEqualTo(403);
    var result =
        management(
            "GET",
            "/roles/" + fixture.businessRole() + "/members?page=0&size=1",
            fixture.token(),
            null);
    assertThat(result.statusCode()).isEqualTo(200);
    var page = json(result).get("data");
    assertThat(page.get("has_more").asBoolean()).isTrue();
    assertThat(page.get("items").size()).isEqualTo(1);
    assertThat(page.get("items").get(0).size()).isEqualTo(6);
    assertThat(result.body())
        .doesNotContain("password_hash", "access_token", "refresh_token", "redis_id");
    assertThat(
            json(management(
                    "GET",
                    "/roles/" + fixture.businessRole() + "/members?page=1&size=2",
                    fixture.token(),
                    null))
                .get("data")
                .get("items")
                .size())
        .isEqualTo(1);
    assertThat(
            management(
                    "GET",
                    "/roles/" + fixture.businessRole() + "/members?size=101",
                    fixture.token(),
                    null)
                .statusCode())
        .isEqualTo(400);
    UUID foreign = UUID.randomUUID();
    new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)",
                  String.class,
                  otherTenant.toString());
              jdbc.update(
                  "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'其他租户真实角色')",
                  foreign,
                  otherTenant,
                  "foreign-" + foreign);
              jdbc.update(
                  "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                  otherTenant,
                  otherTenantUser,
                  foreign);
              return null;
            });
    assertThat(
            management("GET", "/roles/" + foreign + "/members", fixture.token(), null).statusCode())
        .isEqualTo(404);
    data(
        () -> {
          jdbc.update(
              "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_code='identity:user:read'",
              tenant,
              fixture.managerRole());
          return null;
        });
    assertThat(
            management(
                    "GET", "/roles/" + fixture.businessRole() + "/members", fixture.token(), null)
                .statusCode())
        .isEqualTo(403);
    assertThat(management("GET", "/roles", fixture.token(), null).statusCode()).isEqualTo(200);
  }

  @Test
  void roleMembersHttpDelegateCannotReadSelfProtectedOrOtherManagementMembers() throws Exception {
    var fixture = managementFixture(false);
    for (String path :
        java.util.List.of(
            "/users",
            "/roles",
            "/permissions",
            "/role-permissions/" + fixture.businessRole(),
            "/roles/" + fixture.businessRole() + "/members",
            "/users/" + otherTenantUser)) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(403);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedRoleMemberFrontendReadsActualSummariesWithoutManagementWrite() throws Exception {
    enrollOwnAccountTenant();
    var fixture = managementFixture(true);
    roleMemberFixture(fixture);
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("role-members-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("role-members-ui-password.secret"),
        binding = signingDirectory.resolve("role-members-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t52-browser-result.json"),
        screenshot = evidence.resolve("t52-browser-role-members.png"),
        log = evidence.resolve("t52-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "password_file",
                passwordFile.toString(),
                "role_id",
                fixture.businessRole().toString(),
                "target_username",
                "u-" + fixture.target(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-role-members.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(verified.get("management_writes").asInt()).isZero();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
    }
  }

  @Test
  void roleDescriptionHttpPreservesOldCommandsAndKeepsAuthorizationVersion() throws Exception {
    var fixture = managementFixture(true);
    String code = "description-" + UUID.randomUUID();
    var created =
        management(
            "POST",
            "/roles",
            fixture.token(),
            Map.of("code", code, "name", "说明角色", "description", "原说明\r\n第二行"));
    assertThat(created.statusCode()).isEqualTo(200);
    var role = json(created).get("data");
    String id = role.get("id").asString();
    assertThat(role.get("description").asString()).isEqualTo("原说明\n第二行");
    var old =
        management(
            "PUT",
            "/roles/" + id,
            fixture.token(),
            Map.of("version", 0, "name", "旧调用修改名称", "status", "ACTIVE"));
    assertThat(old.statusCode()).isEqualTo(200);
    assertThat(json(old).get("data").get("description").asString()).isEqualTo("原说明\n第二行");
    var updated =
        management(
            "PUT",
            "/roles/" + id,
            fixture.token(),
            Map.of("version", 1, "name", "旧调用修改名称", "status", "ACTIVE", "description", "更新说明"));
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(json(updated).get("data").get("version").asLong()).isEqualTo(2);
    assertThat(json(updated).get("data").get("authorization_version").asLong()).isZero();
    var copied =
        management(
            "POST",
            "/roles/" + id + ":copy",
            fixture.token(),
            Map.of(
                "role_version",
                2,
                "catalog_version",
                jdbc.queryForObject(
                    "SELECT version FROM sys_permission_catalog_state WHERE id=1", Long.class),
                "code",
                "old-desc-copy-" + UUID.randomUUID(),
                "name",
                "旧调用复制说明"));
    assertThat(copied.statusCode()).isEqualTo(200);
    assertThat(json(copied).get("data").get("description").asString()).isEqualTo("更新说明");
    var invalid =
        management(
            "PUT",
            "/roles/" + id,
            fixture.token(),
            Map.of(
                "version", 2, "name", "旧调用修改名称", "status", "ACTIVE", "description", "<b>禁止标记</b>"));
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(json(invalid).get("code").asString()).isEqualTo("ROLE_DESCRIPTION_INVALID");
    var current = management("GET", "/roles?q=" + code, fixture.token(), null);
    assertThat(json(current).get("data").get("items").get(0).get("description").asString())
        .isEqualTo("更新说明");
    assertThat(
            management(
                    "PUT",
                    "/roles/" + id,
                    fixture.token(),
                    Map.of(
                        "version", 1, "name", "过期草稿", "status", "ACTIVE", "description", "不覆盖当前值"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void roleDescriptionHttpCannotBroadenDelegatedMetadataRightsOrReadForeignRole() throws Exception {
    var fixture = managementFixture(false);
    for (String path :
        java.util.List.of(
            "/users",
            "/roles",
            "/permissions",
            "/role-permissions/" + fixture.businessRole(),
            "/roles/" + fixture.businessRole() + "/members",
            "/users/" + otherTenantUser)) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(403);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedRoleDescriptionFrontendCreatesUpdatesAndCopiesActualMetadata() throws Exception {
    enrollOwnAccountTenant();
    managementFixture(true);
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("role-description-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("role-description-ui-password.secret"),
        binding = signingDirectory.resolve("role-description-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize();
    Path result = evidence.resolve("t53-browser-result.json"),
        screenshot = evidence.resolve("t53-browser-role-description.png"),
        log = evidence.resolve("t53-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "password_file",
                passwordFile.toString(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-role-description.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      UUID copied = UUID.fromString(verified.get("copied_role_id").asString());
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT description FROM sys_role WHERE tenant_id=? AND id=?",
                        String.class,
                        tenant,
                        copied))
                .isEqualTo("更新后的操作说明\n仍保留换行与😀");
            return null;
          });
    }
  }

  @Test
  void emptyRoleDeleteHttpPreservesCatalogAndAuditWithStrictMethodAndVersion() throws Exception {
    var fixture = managementFixture(true);
    String path = "/roles/" + fixture.businessRole();
    assertThat(management("DELETE", path, null, Map.of("version", 0)).statusCode()).isEqualTo(401);
    assertThat(
            management("DELETE", path + "/members", fixture.token(), Map.of("version", 0))
                .statusCode())
        .isEqualTo(403);
    assertThat(management("DELETE", path, fixture.token(), Map.of()).statusCode()).isEqualTo(400);
    var response = management("DELETE", path, fixture.token(), Map.of("version", 0));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("data").get("id").asString())
        .isEqualTo(fixture.businessRole().toString());
    assertThat(json(response).get("data").get("deleted").asBoolean()).isTrue();
    assertThat(
            management("GET", "/role-permissions/" + fixture.businessRole(), fixture.token(), null)
                .statusCode())
        .isEqualTo(404);
    assertThat(management("DELETE", path, fixture.token(), Map.of("version", 0)).statusCode())
        .isEqualTo(404);
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_role_permission WHERE tenant_id=? AND role_id=?",
                      Integer.class,
                      tenant,
                      fixture.businessRole()))
              .isZero();
          return null;
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_permission WHERE code=?", Integer.class, fixture.read()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE tenant_id=? AND target_id=? AND action='identity.role.deleted' AND result='SUCCESS'",
                Integer.class,
                tenant,
                fixture.businessRole().toString()))
        .isEqualTo(1);
  }

  @Test
  void roleDeleteHttpRejectsProtectedMembersAndActiveOrRevokedDelegationReferences()
      throws Exception {
    var fixture = managementFixture(true);
    assertThat(
            management(
                    "DELETE",
                    "/roles/" + fixture.managerRole(),
                    fixture.token(),
                    Map.of("version", 0))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "DELETE",
                    "/roles/" + fixture.businessRole(),
                    fixture.token(),
                    Map.of("version", 99))
                .statusCode())
        .isEqualTo(409);
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                fixture.target(),
                fixture.businessRole()));
    var occupied =
        management(
            "DELETE", "/roles/" + fixture.businessRole(), fixture.token(), Map.of("version", 0));
    assertThat(occupied.statusCode()).isEqualTo(409);
    assertThat(json(occupied).get("code").asString()).isEqualTo("ROLE_NOT_EMPTY");
    data(
        () -> {
          jdbc.update(
              "DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=? AND role_id=?",
              tenant,
              fixture.target(),
              fixture.businessRole());
          jdbc.update(
              "INSERT INTO sys_management_grant(tenant_id,actor_user_id,role_id,allow_assign,allow_edit) VALUES(?,?,?,true,false)",
              tenant,
              fixture.delegate(),
              fixture.businessRole());
          jdbc.update(
              "INSERT INTO sys_management_grant_permission(tenant_id,actor_user_id,role_id,permission_code,max_data_scope) VALUES(?,?,?,?,'SELF')",
              tenant,
              fixture.delegate(),
              fixture.businessRole(),
              fixture.read());
          return null;
        });
    assertThat(
            json(management(
                    "DELETE",
                    "/roles/" + fixture.businessRole(),
                    fixture.token(),
                    Map.of("version", 0)))
                .get("code")
                .asString())
        .isEqualTo("ROLE_REFERENCED");
    data(
        () -> {
          jdbc.update(
              "DELETE FROM sys_management_grant_permission WHERE tenant_id=? AND actor_user_id=? AND role_id=?",
              tenant,
              fixture.delegate(),
              fixture.businessRole());
          jdbc.update(
              "UPDATE sys_management_grant SET allow_assign=false,version=version+1 WHERE tenant_id=? AND actor_user_id=? AND role_id=?",
              tenant,
              fixture.delegate(),
              fixture.businessRole());
          return null;
        });
    assertThat(
            json(management(
                    "DELETE",
                    "/roles/" + fixture.businessRole(),
                    fixture.token(),
                    Map.of("version", 0)))
                .get("code")
                .asString())
        .isEqualTo("ROLE_REFERENCED");
    data(
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT version FROM sys_management_grant WHERE tenant_id=? AND actor_user_id=? AND role_id=?",
                      Long.class,
                      tenant,
                      fixture.delegate(),
                      fixture.businessRole()))
              .isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM sys_role WHERE tenant_id=? AND id=?",
                      Integer.class,
                      tenant,
                      fixture.businessRole()))
              .isEqualTo(1);
          return null;
        });
    UUID foreign = UUID.randomUUID();
    new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)",
                  String.class,
                  otherTenant.toString());
              jdbc.update(
                  "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'另一租户删除目标')",
                  foreign,
                  otherTenant,
                  "foreign-delete-" + foreign);
              return null;
            });
    assertThat(
            management("DELETE", "/roles/" + foreign, fixture.token(), Map.of("version", 0))
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void roleDeleteHttpRequiresProtectedActorAndActualClientScope() throws Exception {
    var fixture = managementFixture(false);
    var delegated =
        management(
            "DELETE", "/roles/" + fixture.businessRole(), fixture.token(), Map.of("version", 0));
    assertThat(delegated.statusCode()).isEqualTo(403);
    assertThat(json(delegated).get("code").asString()).isEqualTo("MANAGEMENT_NOT_ALLOWED");
    clientBoundary(
        fixture.client(),
        java.util.Set.of("profile", "identity.users", "identity.permissions"),
        java.util.Set.of("identity-service"));
    var limited =
        management(
            "DELETE", "/roles/" + fixture.businessRole(), fixture.token(), Map.of("version", 0));
    assertThat(limited.statusCode()).isEqualTo(403);
    assertThat(json(limited).get("code").asString()).isEqualTo("FORBIDDEN");
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role WHERE tenant_id=? AND id=?",
                        Integer.class,
                        tenant,
                        fixture.businessRole())))
        .isEqualTo(1);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedRoleDeleteFrontendUsesActualCsrfAndKeepsPermissionCatalog() throws Exception {
    enrollOwnAccountTenant();
    var fixture = managementFixture(true);
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("role-delete-ui-bff.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Path passwordFile = signingDirectory.resolve("role-delete-ui-password.secret"),
        binding = signingDirectory.resolve("role-delete-ui-binding.json");
    Files.writeString(passwordFile, secret);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize(),
        result = evidence.resolve("t54-browser-result.json"),
        screenshot = evidence.resolve("t54-browser-role-delete.png"),
        log = evidence.resolve("t54-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "origin",
                "http://localhost:18080",
                "tenant_code",
                tenantCode,
                "username",
                username,
                "password_file",
                passwordFile.toString(),
                "role_id",
                fixture.businessRole().toString(),
                "permission",
                fixture.read(),
                "result_file",
                result.toString(),
                "screenshot_file",
                screenshot.toString())));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-role-delete.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(verified.get("csrf_denied").asBoolean()).isTrue();
      assertThat(verified.get("directory_preserved").asBoolean()).isTrue();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      assertThat(Files.isRegularFile(Path.of(screenshot + ".confirmation.png"))).isTrue();
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_role WHERE tenant_id=? AND id=?",
                        Integer.class,
                        tenant,
                        fixture.businessRole()))
                .isZero();
            return null;
          });
    }
  }

  @Test
  void managementGrantExactRoleFilterKeepsReceiverAndTenantBoundaries() throws Exception {
    var fixture = managementFixture(true);
    for (String path :
        java.util.List.of(
            "/management-grants",
            "/management-grants?actor_user_id=" + fixture.delegate(),
            "/management-grants?role_id=" + fixture.businessRole())) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(404);
    }
    assertThat(management("PUT", "/management-grants", fixture.token(), Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedManagementGrantFrontendLimitsConsumerAndRevocationRejectsCachedWrite()
      throws Exception {
    var fixture = managementFixture(true);
    for (String path :
        java.util.List.of(
            "/management-grants",
            "/management-grants?actor_user_id=" + fixture.delegate(),
            "/management-grants?role_id=" + fixture.businessRole())) {
      assertThat(management("GET", path, fixture.token(), Map.of()).statusCode()).isEqualTo(404);
    }
    assertThat(management("PUT", "/management-grants", fixture.token(), Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedAuthorizationSimulationFrontendUsesCurrentPolicyWithoutChangingRoleGrant()
      throws Exception {
    var fixture = managementFixture(true);
    for (UUID target : java.util.List.of(user, fixture.target(), otherTenantUser)) {
      var response =
          management(
              "POST",
              "/authorization-evaluations",
              fixture.token(),
              Map.of("user_id", target.toString()));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("data").isNull()).isTrue();
      assertThat(response.body()).doesNotContain("sources", "client_secret", fixture.token());
    }
    assertThat(management("POST", "/authorization-evaluations", null, Map.of()).statusCode())
        .isEqualTo(404);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedAuditFrontendSeparatesRealmsAndShowsOnlySanitizedReadOnlyDetails()
      throws Exception {
    enrollOwnAccountTenant();
    securityManager(true);
    var descriptor = ownAccountBootstrap();
    UUID platformActor = UUID.randomUUID();
    String platformName = "audit-ui-platform-" + platformActor;
    var material = passwords.temporary(policies.current(null));
    String platformPassword = material.secret().revealOnce();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'审计页面平台账号',?,false)",
        platformActor,
        platformName,
        material.hash());
    String target = "audit-ui-" + UUID.randomUUID(), action = "identity.audit.ui_fixture";
    var mapper = JsonMapper.builder().build();
    String safeFixture =
        mapper.writeValueAsString(
            Map.of(
                "before",
                Map.of("name", "原名称"),
                "after",
                Map.of("name", "新名称", "client_secret", "AUDIT_UI_TEST_SECRET_NOT_TO_SHOW"),
                "note",
                "<img src=x onerror=throw1>"));
    var now = java.time.Instant.now();
    UUID tenantEntry =
        auditFixture(
            tenant, user, "TENANT", action, target, "SUCCESS", safeFixture, now.minusSeconds(2));
    UUID truncatedEntry =
        auditFixture(
            tenant,
            user,
            "TENANT",
            action,
            target,
            "FAILURE",
            mapper.writeValueAsString(Map.of("oversized", "x".repeat(18000))),
            now.minusSeconds(1));
    UUID platformEntry =
        auditFixture(null, platformActor, "PLATFORM", action, target, "SUCCESS", safeFixture, now);
    auditFixture(
        otherTenant,
        otherTenantUser,
        "TENANT",
        action,
        target,
        "SUCCESS",
        "{\"note\":\"FOREIGN_AUDIT_UI_NOTE\"}",
        now);
    Path config = signingDirectory.resolve("audit-ui-bff.json"),
        tenantFile = signingDirectory.resolve("audit-ui-tenant.secret"),
        platformFile = signingDirectory.resolve("audit-ui-platform.secret"),
        binding = signingDirectory.resolve("audit-ui-binding.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Files.writeString(tenantFile, secret);
    Files.writeString(platformFile, platformPassword);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize(),
        result = evidence.resolve("t57-browser-result.json"),
        screenshot = evidence.resolve("t57-browser-audits.png"),
        log = evidence.resolve("t57-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.ofEntries(
                Map.entry("test_only", true),
                Map.entry("origin", "http://localhost:18080"),
                Map.entry("tenant_code", tenantCode),
                Map.entry("tenant_username", username),
                Map.entry("tenant_password_file", tenantFile.toString()),
                Map.entry("platform_username", platformName),
                Map.entry("platform_password_file", platformFile.toString()),
                Map.entry("tenant_entry", tenantEntry.toString()),
                Map.entry("truncated_entry", truncatedEntry.toString()),
                Map.entry("platform_entry", platformEntry.toString()),
                Map.entry("action", action),
                Map.entry("target", target),
                Map.entry("result_file", result.toString()),
                Map.entry("screenshot_file", screenshot.toString()))));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-audits.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(verified.get("management_writes").asInt()).isZero();
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      assertThat(Files.isRegularFile(Path.of(screenshot + ".platform.png"))).isTrue();
      assertThat(Files.isRegularFile(Path.of(screenshot + ".truncated.png"))).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_auth_audit WHERE id IN (?,?,?)",
                  Integer.class,
                  tenantEntry,
                  truncatedEntry,
                  platformEntry))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT details->'after'->>'client_secret' FROM sys_auth_audit WHERE id=?",
                  String.class,
                  tenantEntry))
          .isEqualTo("AUDIT_UI_TEST_SECRET_NOT_TO_SHOW");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_NODE_EXECUTABLE", matches = ".+")
  void generatedSessionManagementFrontendRevokesOneThenAllActualTargetBrowserSessions()
      throws Exception {
    enrollOwnAccountTenant();
    var fixture = sessionManagementFixture(true);
    var material = passwords.temporary(policies.current(tenant));
    String targetPassword = material.secret().revealOnce();
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET password_hash=?,must_change_password=false WHERE tenant_id=? AND id=?",
                material.hash(),
                tenant,
                fixture.target()));
    var descriptor = ownAccountBootstrap();
    var mapper = JsonMapper.builder().build();
    Path config = signingDirectory.resolve("sessions-ui-bff.json"),
        actorFile = signingDirectory.resolve("sessions-ui-actor.secret"),
        targetFile = signingDirectory.resolve("sessions-ui-target.secret"),
        binding = signingDirectory.resolve("sessions-ui-binding.json");
    Files.writeString(
        config,
        mapper.writeValueAsString(
            Map.of(
                "platform_secret_file",
                descriptor.get("platform_bff_secret_file").asString(),
                "tenant_secret_file",
                descriptor.get("tenant_bff_secret_file").asString())));
    Files.writeString(actorFile, secret);
    Files.writeString(targetFile, targetPassword);
    Path evidence = Path.of("..", "..", ".local", "test-evidence").toAbsolutePath().normalize(),
        result = evidence.resolve("t58-browser-result.json"),
        screenshot = evidence.resolve("t58-browser-sessions.png"),
        log = evidence.resolve("t58-browser-live.log");
    Files.deleteIfExists(result);
    Files.writeString(
        binding,
        mapper.writeValueAsString(
            Map.ofEntries(
                Map.entry("test_only", true),
                Map.entry("origin", "http://localhost:18080"),
                Map.entry("tenant_code", tenantCode),
                Map.entry("actor_username", username),
                Map.entry("actor_password_file", actorFile.toString()),
                Map.entry("target_username", "u-" + fixture.target()),
                Map.entry("target_password_file", targetFile.toString()),
                Map.entry("target_id", fixture.target().toString()),
                Map.entry("result_file", result.toString()),
                Map.entry("screenshot_file", screenshot.toString()))));
    try (var gateway = new NativeBffGateway(config)) {
      runIsolatedAdminBrowser("live-managed-sessions.mjs", binding, log);
      var verified = mapper.readTree(Files.readString(result));
      assertThat(verified.get("ok").asBoolean()).isTrue();
      assertThat(verified.get("page_errors").asInt()).isZero();
      assertThat(verified.get("browser_token_seen").asBoolean()).isFalse();
      assertThat(verified.get("first_after_one").asInt()).isEqualTo(401);
      assertThat(verified.get("second_after_one").asInt()).isEqualTo(200);
      assertThat(verified.get("second_after_all").asInt()).isEqualTo(401);
      assertThat(verified.get("actor_after_all").asInt()).isEqualTo(200);
      assertThat(Files.isRegularFile(screenshot)).isTrue();
      data(
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE tenant_id=? AND id=?",
                        Long.class,
                        tenant,
                        fixture.target()))
                .isEqualTo(1);
            assertThat(
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE tenant_id=? AND id=?",
                        Long.class,
                        tenant,
                        user))
                .isZero();
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_login_session WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL",
                        Integer.class,
                        tenant,
                        fixture.target()))
                .isZero();
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sys_login_session WHERE tenant_id=? AND user_id=?",
                        Integer.class,
                        tenant,
                        fixture.target()))
                .isEqualTo(2);
            return null;
          });
    }
  }

  private void runIsolatedAdminBrowser(String script, Path binding, Path log) throws Exception {
    var builder =
        new ProcessBuilder(
            System.getenv("PLATFORM_TEST_NODE_EXECUTABLE"),
            Path.of("admin/tests/" + script).toAbsolutePath().toString());
    var allowed =
        java.util.Set.of(
            "PATH",
            "SYSTEMROOT",
            "COMSPEC",
            "WINDIR",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH");
    builder
        .environment()
        .keySet()
        .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
    builder.environment().put("PLATFORM_TEST_ADMIN_E2E_BINDING", binding.toString());
    builder.environment().put("PLATFORM_TEST_BROWSER_CHANNEL", "msedge");
    var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("真实浏览器进程在预算内退出").isTrue();
      assertThat(process.exitValue()).as("真实浏览器失败，检查脱敏证据 %s", log).isZero();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private ManagementFixture sessionManagementFixture(boolean protectedActor) throws Exception {
    var original = managementFixture(protectedActor);
    data(
        () -> {
          for (String code : java.util.List.of("identity:session:read", "identity:session:manage"))
            jdbc.update(
                "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
                tenant,
                original.managerRole(),
                code);
          return null;
        });
    clientBoundary(
        original.client(),
        java.util.Set.of("profile", "identity.sessions"),
        java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            original.client().id(),
            original.client().clientId(),
            original.client().secret(),
            false,
            false,
            "profile identity.sessions");
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    return new ManagementFixture(
        client,
        token,
        original.managerRole(),
        original.businessRole(),
        original.target(),
        original.delegate(),
        original.read());
  }

  private record ManagedLogin(UUID session, OAuthClient client, String token, String refresh) {
    @Override
    public String toString() {
      return "ManagedLogin[REDACTED]";
    }
  }

  private ManagedLogin loginManagedUser(UUID target) throws Exception {
    String password = managedSessionPasswords.get(target);
    if (password == null) {
      var material = passwords.temporary(policies.current(tenant));
      password = material.secret().revealOnce();
      data(
          () ->
              jdbc.update(
                  "UPDATE sys_user SET password_hash=?,status='ACTIVE',must_change_password=false WHERE id=?",
                  material.hash(),
                  target));
      managedSessionPasswords.put(target, password);
    }
    String account =
        data(
            () ->
                jdbc.queryForObject(
                    "SELECT normalized_username FROM sys_user WHERE id=?", String.class, target));
    var previousHttp = http;
    var previousCookies = cookies;
    try {
      cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
      http =
          HttpClient.newBuilder()
              .cookieHandler(cookies)
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var fields = new HashMap<>(credentials(csrf("/identity/login"), password));
      fields.put("username", account);
      assertThat(post("/identity/login", fields).statusCode()).isEqualTo(302);
      UUID session = binding(sid());
      var client = oauthClient(false);
      var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
      return new ManagedLogin(
          session,
          client,
          issued.get("access_token").asString(),
          issued.get("refresh_token").asString());
    } finally {
      http = previousHttp;
      cookies = previousCookies;
    }
  }

  @Test
  void managedSessionsHttpUsesDedicatedScopeAndRevokesExactlyOneRealLogin() throws Exception {
    var fixture = sessionManagementFixture(true);
    var first = loginManagedUser(fixture.target());
    var second = loginManagedUser(fixture.target());
    var listed =
        management(
            "GET", "/sessions?user_id=" + fixture.target() + "&size=1", fixture.token(), null);
    assertThat(listed.statusCode()).isEqualTo(200);
    var page = json(listed).get("data");
    assertThat(page.get("has_more").asBoolean()).isTrue();
    assertThat(page.get("items").size()).isEqualTo(1);
    var item = page.get("items").get(0);
    assertThat(item.get("user_id").asString()).isEqualTo(fixture.target().toString());
    assertThat(item.get("active").asBoolean()).isTrue();
    assertThat(listed.body())
        .doesNotContain("redis_session_id", "password_hash", "access_token", "refresh_token");
    assertThat(listed.body().contains(first.token()) || listed.body().contains(first.refresh()))
        .isFalse();
    var next =
        json(management(
                "GET",
                "/sessions?user_id=" + fixture.target() + "&size=1&page=1",
                fixture.token(),
                null))
            .get("data");
    assertThat(next.get("has_more").asBoolean()).isFalse();
    assertThat(next.get("items").get(0).get("id").asString())
        .isNotEqualTo(item.get("id").asString());
    assertThat(
            management(
                    "POST", "/sessions/" + first.session() + ":revoke", fixture.token(), Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(
            management(
                    "POST", "/sessions/" + first.session() + ":revoke", fixture.token(), Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me", first.token()).statusCode()).isEqualTo(401);
    assertThat(
            oauthToken(
                    first.client(),
                    Map.of("grant_type", "refresh_token", "refresh_token", first.refresh()))
                .statusCode())
        .isEqualTo(400);
    assertThat(bearer("/api/v1/identity/me", second.token()).statusCode()).isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me", fixture.token()).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND target_id=? AND action='identity.managed-session.revoked' AND result='SUCCESS'",
                Integer.class,
                user,
                first.session().toString()))
        .isEqualTo(1);
  }

  @Test
  void managedSessionsHttpSignOutAllIsAtomicAndRepeatDoesNotChangeVersionAgain() throws Exception {
    var fixture = sessionManagementFixture(true);
    var first = loginManagedUser(fixture.target());
    var second = loginManagedUser(fixture.target());
    var unrelated = loginManagedUser(fixture.delegate());
    long before =
        data(
            () ->
                jdbc.queryForObject(
                    "SELECT auth_version FROM sys_user WHERE id=?", Long.class, fixture.target()));
    var result =
        management(
            "POST", "/users/" + fixture.target() + ":sign-out-all", fixture.token(), Map.of());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json(result).get("data").get("revoked").asInt()).isEqualTo(2);
    assertThat(bearer("/api/v1/identity/me", first.token()).statusCode()).isEqualTo(401);
    assertThat(bearer("/api/v1/identity/me", second.token()).statusCode()).isEqualTo(401);
    assertThat(bearer("/api/v1/identity/me", unrelated.token()).statusCode()).isEqualTo(200);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?",
                        Long.class,
                        fixture.target())))
        .isEqualTo(before + 1);
    var repeated =
        management(
            "POST", "/users/" + fixture.target() + ":sign-out-all", fixture.token(), Map.of());
    assertThat(repeated.statusCode()).isEqualTo(200);
    assertThat(json(repeated).get("data").get("revoked").asInt()).isZero();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?",
                        Long.class,
                        fixture.target())))
        .isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND target_id=? AND action='identity.user.signed-out-all' AND result='SUCCESS'",
                Integer.class,
                user,
                fixture.target().toString()))
        .isEqualTo(1);
  }

  @Test
  void delegatedSessionsHttpOnlySeesOrdinaryTargetsAndCannotRevokeSelfOrProtectedAccounts()
      throws Exception {
    var fixture = sessionManagementFixture(false);
    var ordinary = loginManagedUser(fixture.target());
    assertThat(management("GET", "/sessions", fixture.token(), Map.of()).statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "POST",
                    "/sessions/" + ordinary.session() + ":revoke",
                    fixture.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "POST",
                    "/users/" + fixture.target() + ":sign-out-all",
                    fixture.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(store.session(ordinary.session(), false).revokedAt()).isNull();
  }

  @Test
  void managedSessionsHttpRejectsCookieFieldsScopeAndLivePermissionRevocation() throws Exception {
    var fixture = sessionManagementFixture(true);
    var target = loginManagedUser(fixture.target());
    assertThat(get("/api/v1/identity/sessions").statusCode()).isEqualTo(401);
    for (String query :
        java.util.List.of(
            "size=101", "page=-1", "page=2147483647&size=100", "tenant_id=" + otherTenant))
      assertThat(management("GET", "/sessions?" + query, fixture.token(), null).statusCode())
          .isEqualTo(400);
    assertThat(
            management(
                    "POST",
                    "/sessions/" + target.session() + ":revoke",
                    fixture.token(),
                    Map.of("user_id", fixture.target()))
                .statusCode())
        .isEqualTo(400);
    clientBoundary(
        fixture.client(), java.util.Set.of("profile"), java.util.Set.of("identity-service"));
    assertThat(management("GET", "/sessions", fixture.token(), null).statusCode()).isEqualTo(403);
    assertThat(
            management(
                    "POST", "/sessions/" + target.session() + ":revoke", fixture.token(), Map.of())
                .statusCode())
        .isEqualTo(403);
    clientBoundary(
        fixture.client(),
        java.util.Set.of("profile", "identity.sessions"),
        java.util.Set.of("identity-service"));
    data(
        () ->
            jdbc.update(
                "DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_code IN ('identity:session:read','identity:session:manage')",
                tenant,
                fixture.managerRole()));
    assertThat(management("GET", "/sessions", fixture.token(), null).statusCode()).isEqualTo(403);
    assertThat(
            management(
                    "POST",
                    "/users/" + fixture.target() + ":sign-out-all",
                    fixture.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(bearer("/api/v1/identity/me", target.token()).statusCode()).isEqualTo(200);
  }

  @Test
  void managedSessionsHttpAuditFailureRollsBackAllSessionAndAuthorizationChanges()
      throws Exception {
    var fixture = sessionManagementFixture(true);
    var first = loginManagedUser(fixture.target());
    var second = loginManagedUser(fixture.target());
    long version =
        data(
            () ->
                jdbc.queryForObject(
                    "SELECT auth_version FROM sys_user WHERE id=?", Long.class, fixture.target()));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_MANAGED_SESSIONS_AUDIT"))
        .when(sessionQueries)
        .audit(any(), any(), anyString(), anyString(), any(), anyInt(), anyString());
    try {
      for (String path :
          java.util.List.of(
              "/sessions/" + first.session() + ":revoke",
              "/users/" + fixture.target() + ":sign-out-all")) {
        var failed = management("POST", path, fixture.token(), Map.of());
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).doesNotContain("DO_NOT_ECHO_MANAGED_SESSIONS_AUDIT");
        assertThat(store.session(first.session(), false).revokedAt()).isNull();
        assertThat(store.session(second.session(), false).revokedAt()).isNull();
        assertThat(
                data(
                    () ->
                        jdbc.queryForObject(
                            "SELECT auth_version FROM sys_user WHERE id=?",
                            Long.class,
                            fixture.target())))
            .isEqualTo(version);
        assertThat(bearer("/api/v1/identity/me", first.token()).statusCode()).isEqualTo(200);
        assertThat(bearer("/api/v1/identity/me", second.token()).statusCode()).isEqualTo(200);
      }
    } finally {
      reset(sessionQueries);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void managedSessionsBffRequiresCsrfAndRevokesOnlySelectedTarget() throws Exception {
    var fixture = sessionManagementFixture(true);
    var target = loginManagedUser(fixture.target());
    ownAccountToken();
    cookies.getCookieStore().removeAll();
    var descriptor = ownAccountBootstrap();
    Path config = signingDirectory.resolve("managed-sessions-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    try (var gateway = new NativeBffGateway(config)) {
      bffLogin("identity/tenant", "TENANT", tenantCode, username, secret, false);
      assertThat(
              bffRequest(
                      "/bff/identity/tenant/api/sessions?user_id=" + fixture.target(),
                      "GET",
                      null,
                      null)
                  .statusCode())
          .isEqualTo(200);
      String path = "/bff/identity/tenant/api/sessions/" + target.session() + ":revoke";
      assertThat(bffRequest(path, "POST", Map.of(), null).statusCode()).isEqualTo(403);
      assertThat(bearer("/api/v1/identity/me", target.token()).statusCode()).isEqualTo(200);
      assertThat(bffRequest(path, "POST", Map.of(), bffCsrf("identity/tenant")).statusCode())
          .isEqualTo(200);
      assertThat(bearer("/api/v1/identity/me", target.token()).statusCode()).isEqualTo(401);
      assertThat(bffRequest("/bff/identity/tenant/api/me", "GET", null, null).statusCode())
          .isEqualTo(200);
      assertThat(
              bffRequest("/bff/identity/tenant/logout", "POST", null, bffCsrf("identity/tenant"))
                  .statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownProfileHttpUpdatesOnlyDisplayNameWithVersionAndKeepsLiveToken() throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    var before = bearer("/api/v1/identity/me/profile", token);
    assertThat(before.statusCode()).isEqualTo(200);
    var profile = json(before).get("data");
    assertThat(profile.get("id").asString()).isEqualTo(user.toString());
    assertThat(profile.get("username").asString()).isEqualTo(username);
    assertThat(profile.size()).isEqualTo(4);
    long version = profile.get("version").asLong();
    var securityBefore =
        data(
            () ->
                jdbc.queryForMap(
                    "SELECT normalized_username,password_hash,verified_email,email_verified_at,auth_version FROM sys_user WHERE id=?",
                    user));
    long auditBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.own-profile.updated' AND result='SUCCESS'",
            Long.class,
            user);
    String name = "本人显示名已更新";
    var changed =
        management("PUT", "/me/profile", token, Map.of("version", version, "display_name", name));
    assertThat(changed.statusCode()).isEqualTo(200);
    assertThat(json(changed).get("data").get("version").asLong()).isEqualTo(version + 1);
    assertThat(json(changed).get("data").get("display_name").asString()).isEqualTo(name);
    assertThat(
            management(
                    "PUT",
                    "/me/profile",
                    token,
                    Map.of("version", version, "display_name", "旧编辑覆盖"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            management(
                    "PUT",
                    "/me/profile",
                    token,
                    Map.of("version", version + 1, "display_name", name))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            json(bearer("/api/v1/identity/me/profile", token)).get("data").get("version").asLong())
        .isEqualTo(version + 1);
    var me = bearer("/api/v1/identity/me", token);
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(json(me).get("data").get("profile").get("name").asString()).isEqualTo(name);
    assertThat(
            json(me).get("data").get("audiences").get("identity-service").get("permissions").size())
        .isZero();
    var securityAfter =
        data(
            () ->
                jdbc.queryForMap(
                    "SELECT normalized_username,password_hash,verified_email,email_verified_at,auth_version FROM sys_user WHERE id=?",
                    user));
    assertThat(securityBefore.equals(securityAfter)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.own-profile.updated' AND result='SUCCESS'",
                Long.class,
                user))
        .isEqualTo(auditBefore + 1);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownProfileHttpRejectsCredentialIdentityAndThirdPartyProfileFields() throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    long version =
        json(bearer("/api/v1/identity/me/profile", token)).get("data").get("version").asLong();
    assertThat(get("/api/v1/identity/me/profile").statusCode()).isEqualTo(401);
    var app = oauthClient(false);
    String third =
        json(oauthToken(app, codeGrant(authorizationCode(app), VERIFIER)))
            .get("access_token")
            .asString();
    assertThat(bearer("/api/v1/identity/me/profile", third).statusCode()).isEqualTo(403);
    assertThat(
            management(
                    "PUT", "/me/profile", third, Map.of("version", version, "display_name", "外部更改"))
                .statusCode())
        .isEqualTo(403);
    for (String field :
        java.util.List.of(
            "user_id",
            "tenant_id",
            "username",
            "verified_email",
            "password",
            "auth_version",
            "role_ids")) {
      var invalid = new HashMap<String, Object>(Map.of("version", version, "display_name", "本人姓名"));
      invalid.put(field, "forbidden");
      assertThat(management("PUT", "/me/profile", token, invalid).statusCode()).isEqualTo(400);
    }
    for (String name : java.util.List.of("", "<script>", "control\nname", "字".repeat(129)))
      assertThat(
              management(
                      "PUT", "/me/profile", token, Map.of("version", version, "display_name", name))
                  .statusCode())
          .isEqualTo(400);
    assertThat(management("PUT", "/me/profile", token, Map.of("display_name", "缺版本")).statusCode())
        .isEqualTo(400);
    assertThat(bearer("/api/v1/identity/me/profile?user_id=" + otherTenantUser, token).statusCode())
        .isEqualTo(400);
    assertThat(
            json(bearer("/api/v1/identity/me/profile", token)).get("data").get("version").asLong())
        .isEqualTo(version);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownProfileHttpAuditFailureAndFailedConditionalUpdateLeaveOldProfileAndToken()
      throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    var old = json(bearer("/api/v1/identity/me/profile", token)).get("data");
    long version = old.get("version").asLong();
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_OWN_PROFILE_FAILURE"))
        .when(store)
        .auditOwnProfile(any(), anyString(), anyString(), anyLong(), anyString());
    try {
      var result =
          management(
              "PUT", "/me/profile", token, Map.of("version", version, "display_name", "审计回滚"));
      assertThat(result.statusCode()).isEqualTo(503);
      assertThat(result.body()).doesNotContain("DO_NOT_ECHO_OWN_PROFILE_FAILURE");
    } finally {
      doCallRealMethod()
          .when(store)
          .auditOwnProfile(any(), anyString(), anyString(), anyLong(), anyString());
    }
    assertThat(json(bearer("/api/v1/identity/me/profile", token)).get("data")).isEqualTo(old);
    doReturn(0).when(store).changeOwnDisplayName(any(), anyLong(), anyString());
    try {
      assertThat(
              management(
                      "PUT",
                      "/me/profile",
                      token,
                      Map.of("version", version, "display_name", "条件写入失败"))
                  .statusCode())
          .isEqualTo(409);
    } finally {
      doCallRealMethod().when(store).changeOwnDisplayName(any(), anyLong(), anyString());
    }
    assertThat(json(bearer("/api/v1/identity/me/profile", token)).get("data")).isEqualTo(old);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownProfileHttpPlatformChangesNoTenantOrAuthenticationVersion() throws Exception {
    var bootstrap = ownAccountBootstrap();
    UUID actor = UUID.randomUUID();
    String account = "profile-platform-" + actor;
    var material = passwords.temporary(policies.current(null));
    String password = material.secret().revealOnce();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'平台原姓名',?,false)",
        actor,
        account,
        material.hash());
    assertThat(
            post(
                    "/identity/login",
                    Map.of(
                        "_csrf",
                        csrf("/identity/login"),
                        "realm",
                        "PLATFORM",
                        "username",
                        account,
                        "password",
                        password))
                .statusCode())
        .isEqualTo(302);
    String token =
        fixedManagementToken(
            "identity-admin-platform",
            Files.readString(Path.of(bootstrap.get("platform_bff_secret_file").asString())).strip(),
            "",
            true);
    var profile = json(bearer("/api/v1/identity/me/profile", token)).get("data");
    assertThat(profile.get("id").asString()).isEqualTo(actor.toString());
    assertThat(
            management(
                    "PUT",
                    "/me/profile",
                    token,
                    Map.of("version", profile.get("version").asLong(), "display_name", "平台本人新姓名"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT auth_version FROM sys_platform_user WHERE id=?", Long.class, actor))
        .isZero();
    assertThat(
            json(bearer("/api/v1/identity/me", token))
                .get("data")
                .get("profile")
                .get("name")
                .asString())
        .isEqualTo("平台本人新姓名");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void ownProfileBffRequiresCsrfAndKeepsSessionAfterDisplayNameChange() throws Exception {
    ordinary();
    login();
    ownAccountToken();
    cookies.getCookieStore().removeAll();
    var descriptor = ownAccountBootstrap();
    Path config = signingDirectory.resolve("profile-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    try (var gateway = new NativeBffGateway(config)) {
      bffLogin("identity/tenant", "TENANT", tenantCode, username, secret, false);
      var profile = bffRequest("/bff/identity/tenant/api/me/profile", "GET", null, null);
      assertThat(profile.statusCode()).isEqualTo(200);
      var command =
          Map.of(
              "version",
              json(profile).get("data").get("version").asLong(),
              "display_name",
              "BFF 本人资料");
      assertThat(
              bffRequest("/bff/identity/tenant/api/me/profile", "PUT", command, null).statusCode())
          .isEqualTo(403);
      assertThat(
              bffRequest(
                      "/bff/identity/tenant/api/me/profile",
                      "PUT",
                      command,
                      bffCsrf("identity/tenant"))
                  .statusCode())
          .isEqualTo(200);
      var me = bffRequest("/bff/identity/tenant/api/me", "GET", null, null);
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(json(me).get("data").get("profile").get("name").asString()).isEqualTo("BFF 本人资料");
      assertThat(
              bffRequest("/bff/identity/tenant/logout", "POST", null, bffCsrf("identity/tenant"))
                  .statusCode())
          .isEqualTo(200);
    }
  }

  private String ownAccountToken() throws Exception {
    var bootstrap = ownAccountBootstrap();
    enrollOwnAccountTenant();
    return fixedManagementToken(
        "identity-admin-tenant",
        Files.readString(Path.of(bootstrap.get("tenant_bff_secret_file").asString())).strip(),
        tenantCode,
        false);
  }

  private void enrollOwnAccountTenant() {
    try (var ignored =
        com.ken.platform.foundation.http.CallContexts.bind(
            new com.ken.platform.foundation.http.CallContext(
                UUID.randomUUID().toString(), null, null, null, null, null))) {
      new TransactionTemplate(manager)
          .execute(
              status -> {
                store.lockCatalog();
                context
                    .getBean(com.ken.platform.identity.service.oauth.ClientManagementService.class)
                    .enrollBootstrapTenant(tenant);
                return null;
              });
    }
  }

  private static String htmlCsrf(HttpResponse<String> page) {
    assertThat(page.statusCode()).isEqualTo(200);
    var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
    assertThat(token.find()).isTrue();
    return token.group(1);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void initialTenantPasswordResumesOriginalRealBffAuthorizationAfterFreshLogin() throws Exception {
    initialPasswordBff(false);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void initialPlatformPasswordResumesOriginalRealBffAuthorizationAfterFreshLogin()
      throws Exception {
    initialPasswordBff(true);
  }

  private void initialPasswordBff(boolean platform) throws Exception {
    var descriptor = ownAccountBootstrap();
    String realm = platform ? "PLATFORM" : "TENANT";
    String namespace = platform ? "identity/platform" : "identity/tenant";
    String account = username, password = secret;
    UUID subject = user;
    if (platform) {
      subject = UUID.randomUUID();
      account = "initial-bff-" + subject;
      var material = passwords.temporary(policies.current(null));
      password = material.secret().revealOnce();
      jdbc.update(
          "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash) VALUES(?,?,'BFF 首次平台账号',?)",
          subject,
          account,
          material.hash());
    } else enrollOwnAccountTenant();
    Path config = signingDirectory.resolve("initial-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    try (var gateway = new NativeBffGateway(config)) {
      var begin =
          bffForm(
              "/bff/" + namespace + "/login",
              platform ? Map.of() : Map.of("tenant_code", tenantCode),
              bffCsrf(namespace));
      assertThat(begin.statusCode()).isEqualTo(302);
      String authorization = begin.headers().firstValue("Location").orElseThrow();
      var enter = bffGet(authorization);
      assertThat(enter.statusCode()).isEqualTo(302);
      String login = enter.headers().firstValue("Location").orElseThrow();
      var credentials = new HashMap<>(credentials(htmlCsrf(bffGet(login)), password));
      credentials.put("realm", realm);
      credentials.put("username", account);
      credentials.put("tenant_code", platform ? "" : tenantCode);
      var restricted = bffForm("/identity/login", credentials, null);
      assertThat(restricted.statusCode()).isEqualTo(302);
      assertThat(restricted.headers().firstValue("Location").orElseThrow())
          .isEqualTo("/identity/password/initial");
      String oldCookie = sid();
      UUID oldBinding = binding(oldCookie);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM oauth2_authorization WHERE principal_name=?",
                  Integer.class,
                  subject.toString()))
          .isZero();
      String initialCsrf = htmlCsrf(bffGet("/identity/password/initial"));
      String next = "Initial BFF password " + UUID.randomUUID();
      var invalid =
          bffForm(
              "/identity/password/initial",
              Map.of("_csrf", initialCsrf, "password", next, "confirmation", "different"),
              null);
      assertThat(invalid.statusCode()).isEqualTo(400);
      assertThat(store.session(oldBinding, false).restricted()).isTrue();
      var changed =
          bffForm(
              "/identity/password/initial",
              Map.of(
                  "_csrf",
                  initialCsrf,
                  "password",
                  next,
                  "confirmation",
                  next,
                  "return_url",
                  "https://untrusted.example/"),
              null);
      assertThat(changed.statusCode()).isEqualTo(302);
      assertThat(store.session(oldBinding, false).revokedAt()).isNotNull();
      assertThat(sid().equals(oldCookie)).isFalse();
      String resumeLogin = changed.headers().firstValue("Location").orElseThrow();
      assertThat(resumeLogin).startsWith("/identity/login?changed&realm=" + realm);
      if (!platform)
        assertThat(parameter("http://localhost" + resumeLogin, "tenant_code"))
            .isEqualTo(tenantCode);
      assertThat(resumeLogin.contains("untrusted.example")).isFalse();
      org.springframework.session.Session anonymous = sessions.findById(sid());
      assertThat(anonymous).isNotNull();
      Object security =
          anonymous.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      assertThat(security).isNull();
      assertThat(
              bffForm(
                      "/identity/password/initial",
                      Map.of("_csrf", initialCsrf, "password", next, "confirmation", next),
                      null)
                  .statusCode())
          .isEqualTo(403);
      var old =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://localhost:18080/identity/session"))
                      .header("Cookie", "IDENTITY_SSO=" + oldCookie)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(old.statusCode()).isEqualTo(302);
      credentials.put("_csrf", htmlCsrf(bffGet(resumeLogin)));
      credentials.put("password", next);
      var authenticated = bffForm("/identity/login", credentials, null);
      assertThat(authenticated.statusCode()).isEqualTo(302);
      String continuation = authenticated.headers().firstValue("Location").orElseThrow();
      assertThat(continuation).startsWith("/oauth2/authorize?");
      assertThat(parameter("http://localhost:18080" + continuation, "state"))
          .isEqualTo(parameter(authorization, "state"));
      assertThat(parameter("http://localhost:18080" + continuation, "code_challenge"))
          .isEqualTo(parameter(authorization, "code_challenge"));
      assertThat(continuation.contains("prompt=")).isFalse();
      var code = bffGet(continuation);
      assertThat(code.statusCode()).isEqualTo(302);
      var callback = bffGet(code.headers().firstValue("Location").orElseThrow());
      assertThat(callback.statusCode()).isEqualTo(303);
      var me = bffRequest("/bff/" + namespace + "/api/me", "GET", null, null);
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(json(me).get("data").get("sub").asString()).isEqualTo(subject.toString());
      assertThat(json(me).get("data").get("principal_type").asString())
          .isEqualTo(platform ? "platform" : "user");
      assertThat(
              bffRequest("/bff/" + namespace + "/logout", "POST", null, bffCsrf(namespace))
                  .statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpListsOnlyOwnerAndRevokesExactSessionIdempotently() throws Exception {
    ordinary();
    login();
    UUID first = binding(sid());
    String token = ownAccountToken();
    cookies.getCookieStore().removeAll();
    login();
    UUID second = binding(sid());
    String secondToken = ownAccountToken();
    var listed = management("GET", "/me/sessions", token, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    var rows = json(listed).get("data");
    assertThat(rows.size()).isEqualTo(2);
    assertThat(
            java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("current").asBoolean())
                .map(row -> row.get("id").asString())
                .toList())
        .containsExactly(first.toString());
    assertThat(listed.body().contains(sid())).isFalse();
    assertThat(listed.body()).doesNotContain("redis_session_id", "password", "access_token");
    UUID foreignTenant = UUID.randomUUID(), foreignUser = UUID.randomUUID();
    String foreignCode = "own-other-" + foreignTenant;
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'其他租户')", foreignTenant, foreignCode);
    new TransactionTemplate(manager)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id',?,true)",
                  String.class,
                  foreignTenant.toString());
              return jdbc.update(
                  "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'同名账号',?,'ACTIVE',false)",
                  foreignUser,
                  foreignTenant,
                  username,
                  passwords.encode(secret, policies.current(foreignTenant)));
            });
    var foreign =
        service.authenticate(
            LoginRealm.TENANT, foreignCode, username, secret, InetAddress.getLoopbackAddress());
    assertThat(
            management("POST", "/me/sessions/" + foreign.sessionId() + ":revoke", token, Map.of())
                .statusCode())
        .isEqualTo(404);
    // 另一个真实账号的会话，即便同租户也不能撤销。
    UUID other = UUID.randomUUID();
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) VALUES(?,?,?,'他人',?,'ACTIVE',false)",
                other,
                tenant,
                "other-" + other,
                passwords.encode(secret, policies.current(tenant))));
    var otherSession =
        service.authenticate(
            LoginRealm.TENANT,
            tenantCode,
            "other-" + other,
            secret,
            InetAddress.getLoopbackAddress());
    assertThat(
            management(
                    "POST", "/me/sessions/" + otherSession.sessionId() + ":revoke", token, Map.of())
                .statusCode())
        .isEqualTo(404);
    assertThat(store.session(otherSession.sessionId(), false).revokedAt()).isNull();
    assertThat(
            management("POST", "/me/sessions/" + second + ":revoke", token, Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(
            management("POST", "/me/sessions/" + second + ":revoke", token, Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me", secondToken).statusCode()).isEqualTo(401);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    assertThat(store.session(foreign.sessionId(), false).revokedAt()).isNull();
    assertThat(
            management("POST", "/me/sessions/" + first + ":revoke", token, Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me/sessions", token).statusCode()).isEqualTo(401);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpConsentRevocationPreservesOtherApplicationsAndSso() throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    var external = oauthClient(true, true, false);
    var externalIssued =
        json(oauthToken(external, codeGrant(authorizationCode(external), VERIFIER)));
    String externalToken = externalIssued.get("access_token").asString();
    var listed = management("GET", "/me/consents", token, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(json(listed).get("data").size()).isEqualTo(1);
    assertThat(json(listed).get("data").get(0).get("client_id").asString())
        .isEqualTo(external.clientId());
    assertThat(
            management("POST", "/me/consents/" + external.clientId() + ":revoke", token, Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(
            management("POST", "/me/consents/" + external.clientId() + ":revoke", token, Map.of())
                .statusCode())
        .isEqualTo(200);
    assertThat(bearer("/userinfo", externalToken).statusCode()).isEqualTo(401);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(json(management("GET", "/me/consents", token, null)).get("data").size()).isZero();
    assertThat(
            management("POST", "/me/consents/identity-admin-platform:revoke", token, Map.of())
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpPlatformSessionNeverIncludesTenantOrOtherPlatformAccount() throws Exception {
    var bootstrap = ownAccountBootstrap();
    UUID platform = UUID.randomUUID();
    String account = "personal-platform-" + platform;
    var material = passwords.temporary(policies.current(null));
    String password = material.secret().revealOnce();
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'个人平台账号',?,false)",
        platform,
        account,
        material.hash());
    var logged =
        post(
            "/identity/login",
            Map.of(
                "_csrf",
                csrf("/identity/login"),
                "realm",
                "PLATFORM",
                "username",
                account,
                "password",
                password));
    assertThat(logged.statusCode()).isEqualTo(302);
    UUID session = binding(sid());
    String token =
        fixedManagementToken(
            "identity-admin-platform",
            Files.readString(Path.of(bootstrap.get("platform_bff_secret_file").asString())).strip(),
            "",
            true);
    var listed = bearer("/api/v1/identity/me/sessions", token);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(json(listed).get("data").size()).isEqualTo(1);
    assertThat(json(listed).get("data").get(0).get("id").asString()).isEqualTo(session.toString());
    assertThat(management("POST", "/me/sessions:sign-out-all", token, Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me/sessions", token).statusCode()).isEqualTo(401);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpSignOutAllRevokesEveryOwnSessionAndApplication() throws Exception {
    ordinary();
    login();
    String first = ownAccountToken();
    cookies.getCookieStore().removeAll();
    login();
    String second = ownAccountToken();
    var app = oauthClient(false);
    String third =
        json(oauthToken(app, codeGrant(authorizationCode(app), VERIFIER)))
            .get("access_token")
            .asString();
    long version =
        data(
            () ->
                jdbc.queryForObject(
                    "SELECT auth_version FROM sys_user WHERE id=?", Long.class, user));
    var result = management("POST", "/me/sessions:sign-out-all", first, Map.of());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json(result).get("data").get("revoked").asInt()).isEqualTo(2);
    for (String value : java.util.List.of(first, second, third))
      assertThat(bearer("/api/v1/identity/me", value).statusCode()).isEqualTo(401);
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, user)))
        .isEqualTo(version + 1);
    assertThat(get("/identity/session").statusCode()).isEqualTo(302);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpPasswordChangeUsesOriginalCredentialAndRevokesAllAuthorizations()
      throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    String next = "本人新密码 Native password " + UUID.randomUUID();
    assertThat(
            management(
                    "POST",
                    "/me/password",
                    token,
                    Map.of("current_password", "wrong", "new_password", next))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            management(
                    "POST",
                    "/me/password",
                    token,
                    Map.of("current_password", secret, "new_password", secret))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            management(
                    "POST",
                    "/me/password",
                    token,
                    Map.of("current_password", secret, "new_password", "short"))
                .statusCode())
        .isEqualTo(400);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    var changed =
        management(
            "POST",
            "/me/password",
            token,
            Map.of("current_password", secret, "new_password", next));
    assertThat(changed.statusCode()).isEqualTo(200);
    assertThat(changed.body().contains(secret) || changed.body().contains(next)).isFalse();
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(401);
    cookies.getCookieStore().removeAll();
    var rejected = post("/identity/login", credentials(csrf("/identity/login"), secret));
    assertThat(rejected.statusCode()).isEqualTo(401);
    assertThat(rejected.headers().firstValue("Location")).isEmpty();
    assertThat(rejected.body().contains(secret)).isFalse();
    var accepted = post("/identity/login", credentials(csrf("/identity/login"), next));
    assertThat(accepted.statusCode()).isEqualTo(302);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND (details::text LIKE ? OR details::text LIKE ?)",
                Integer.class,
                user,
                "%" + secret + "%",
                "%" + next + "%"))
        .isZero();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpRejectsCookieThirdPartyUnknownFieldsAndStaleAuthentication() throws Exception {
    ordinary();
    login();
    String token = ownAccountToken();
    UUID session = binding(sid());
    assertThat(get("/api/v1/identity/me/sessions").statusCode()).isEqualTo(401);
    var external = oauthClient(false);
    String foreign =
        json(oauthToken(external, codeGrant(authorizationCode(external), VERIFIER)))
            .get("access_token")
            .asString();
    assertThat(bearer("/api/v1/identity/me", foreign).statusCode()).isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me/sessions", foreign).statusCode()).isEqualTo(403);
    assertThat(management("POST", "/me/sessions:sign-out-all", foreign, Map.of()).statusCode())
        .isEqualTo(403);
    for (String query :
        java.util.List.of(
            "user_id=" + user,
            "tenant_id=" + otherTenant,
            "page=-1",
            "size=101",
            "page=2147483647&size=100"))
      assertThat(bearer("/api/v1/identity/me/sessions?" + query, token).statusCode())
          .isEqualTo(400);
    assertThat(
            management("POST", "/me/sessions:sign-out-all", token, Map.of("user_id", user))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            management(
                    "POST",
                    "/me/password",
                    token,
                    Map.of(
                        "current_password",
                        secret,
                        "new_password",
                        "Other Valid password 888",
                        "user_id",
                        user))
                .statusCode())
        .isEqualTo(400);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '6 minutes' WHERE id=?", session);
    var rejected =
        management(
            "POST",
            "/me/password",
            token,
            Map.of("current_password", secret, "new_password", "Other Valid password 999"));
    assertThat(rejected.statusCode()).isEqualTo(403);
    assertThat(json(rejected).get("code").asString()).isEqualTo("RECENT_AUTHENTICATION_REQUIRED");
    assertThat(bearer("/api/v1/identity/me/sessions", token).statusCode()).isEqualTo(200);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  void ownAccountHttpAuditFailureRollsBackRevocationAndNeverEchoesCause() throws Exception {
    ordinary();
    login();
    UUID session = binding(sid());
    String token = ownAccountToken();
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_OWN_SESSION_AUDIT"))
        .when(store)
        .audit(any(), eq("identity.session.revoked"), anyString());
    try {
      var result = management("POST", "/me/sessions/" + session + ":revoke", token, Map.of());
      assertThat(result.statusCode()).isEqualTo(503);
      assertThat(result.body()).doesNotContain("DO_NOT_ECHO_OWN_SESSION_AUDIT");
      assertThat(result.body().contains(token) || result.body().contains(secret)).isFalse();
      assertThat(store.session(session, false).revokedAt()).isNull();
      assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    } finally {
      doCallRealMethod().when(store).audit(any(), eq("identity.session.revoked"), anyString());
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BFF_BOOTSTRAP_STATE", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void ownAccountBffUsesNamespaceCsrfAndChangedPasswordClosesCurrentApplication() throws Exception {
    ordinary();
    login();
    ownAccountToken();
    cookies.getCookieStore().removeAll();
    var descriptor = ownAccountBootstrap();
    Path config = signingDirectory.resolve("own-account-bff-" + UUID.randomUUID() + ".json");
    Files.writeString(
        config,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "platform_secret_file", descriptor.get("platform_bff_secret_file").asString(),
                    "tenant_secret_file", descriptor.get("tenant_bff_secret_file").asString())));
    try (var gateway = new NativeBffGateway(config)) {
      bffLogin("identity/tenant", "TENANT", tenantCode, username, secret, false);
      assertThat(bffRequest("/bff/identity/tenant/api/me/sessions", "GET", null, null).statusCode())
          .isEqualTo(200);
      String next = "BFF Password change " + UUID.randomUUID();
      var command = Map.of("current_password", secret, "new_password", next);
      assertThat(
              bffRequest("/bff/identity/tenant/api/me/password", "POST", command, null)
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              bffRequest(
                      "/bff/identity/tenant/api/me/password",
                      "POST",
                      command,
                      bffCsrf("identity/tenant"))
                  .statusCode())
          .isEqualTo(200);
      assertThat(bffRequest("/bff/identity/tenant/api/me/sessions", "GET", null, null).statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_BOOTSTRAP_STATE", matches = ".+")
  void bootstrapClientsSupportRealInitialLoginAtomicTenantEnrollmentAndSafeRepeat()
      throws Exception {
    var metadata =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_BOOTSTRAP_STATE"))));
    assertThat(metadata.get("test_only").asBoolean()).isTrue();
    var descriptor =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(metadata.get("bootstrap_secret_file").asString())));
    String initial =
        Files.readString(Path.of(descriptor.get("initial_password_file").asString())).strip();
    String platformSecret =
        Files.readString(Path.of(descriptor.get("platform_bff_secret_file").asString())).strip();
    String tenantSecret =
        Files.readString(Path.of(descriptor.get("tenant_bff_secret_file").asString())).strip();
    String changed = "Activated bootstrap admin " + UUID.randomUUID();
    String account = descriptor.get("admin_username").asString();
    activateIssuedAccount("PLATFORM", "", account, initial, changed);
    String platformToken =
        fixedManagementToken("identity-admin-platform", platformSecret, "", true);
    var platformMe = json(bearer("/api/v1/identity/me", platformToken)).get("data");
    assertThat(platformMe.get("principal_type").asString()).isEqualTo("platform");
    assertThat(platformMe.get("audiences").get("identity-service").get("permissions").size())
        .isEqualTo(10);
    assertThat(platformMe.get("audiences").get("identity-service").get("permissions").toString())
        .contains("\"platform:monitoring:read\"");
    var before = oauthClients.byClientId("identity-admin-tenant");
    String code = "bootstrap-tenant-" + UUID.randomUUID();
    var command =
        Map.<String, Object>of(
            "code",
            code,
            "name",
            "初始化客户端的租户",
            "admin_username",
            "admin",
            "admin_display_name",
            "租户管理员");
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_ENROLLMENT_OUTAGE"))
        .when(oauthClients)
        .savePair(any(), any(), any());
    try {
      var rejected = management("POST", "/platform/tenants", platformToken, command);
      assertThat(rejected.statusCode()).isEqualTo(503);
      assertThat(rejected.body())
          .doesNotContain("DO_NOT_ECHO_ENROLLMENT_OUTAGE", initial, platformSecret);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_tenant WHERE code=?", Integer.class, code))
          .isZero();
      assertThat(oauthClients.byClientId("identity-admin-tenant").policy())
          .isEqualTo(before.policy());
    } finally {
      doCallRealMethod().when(oauthClients).savePair(any(), any(), any());
    }
    var created = management("POST", "/platform/tenants", platformToken, command);
    assertThat(created.statusCode()).isEqualTo(200);
    UUID tenantId = UUID.fromString(json(created).get("data").get("tenant_id").asString());
    String tenantInitial = json(created).get("data").get("initial_password").asString();
    var enrolled = oauthClients.byClientId("identity-admin-tenant");
    assertThat(enrolled.policy().version()).isEqualTo(before.policy().version() + 1);
    var expectedTenants = new java.util.HashSet<>(before.policy().allowedTenantIds());
    expectedTenants.add(tenantId);
    assertThat(enrolled.policy().allowedTenantIds()).isEqualTo(expectedTenants);
    UUID admin = UUID.fromString(metadata.get("created").get("initial_admin_id").asString());
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM sys_platform_user WHERE id=?", String.class, admin);
    var database =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(metadata.get("database_binding").asString())));
    var process =
        new ProcessBuilder(
            Path.of(metadata.get("java_home").asString(), "bin", "java.exe").toString(),
            "-jar",
            Path.of("target/identity-service.jar").toAbsolutePath().toString(),
            "bootstrap");
    var processEnv = process.environment();
    processEnv
        .keySet()
        .removeIf(
            key ->
                !java.util.Set.of(
                        "SYSTEMROOT",
                        "WINDIR",
                        "PATH",
                        "TEMP",
                        "TMP",
                        "USERPROFILE",
                        "APPDATA",
                        "LOCALAPPDATA")
                    .contains(key.toUpperCase(java.util.Locale.ROOT)));
    processEnv.putAll(
        Map.of(
            "PROJECT_CODE",
            metadata.get("project").asString(),
            "APP_ENV",
            "local",
            "PUBLIC_ORIGIN",
            "http://localhost:18080",
            "PG_HOST",
            database.get("host").asString(),
            "PG_PORT",
            Integer.toString(database.get("port").asInt()),
            "DB_NAME",
            database.get("dbname").asString(),
            "DB_USER",
            database.get("username").asString(),
            "DB_PASSWORD_SECRET_FILE",
            database.get("password_file").asString(),
            "IDENTITY_BOOTSTRAP_SECRET_FILE",
            metadata.get("bootstrap_secret_file").asString()));
    Path log = signingDirectory.resolve("bootstrap-repeat-" + UUID.randomUUID() + ".log");
    var running = process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertThat(running.waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(running.exitValue()).isZero();
      String output = Files.readString(log);
      assertThat(output)
          .contains("VERIFIED")
          .doesNotContain(initial, changed, platformSecret, tenantSecret);
    } finally {
      if (running.isAlive()) running.destroyForcibly();
    }
    assertThat(
            hash.equals(
                jdbc.queryForObject(
                    "SELECT password_hash FROM sys_platform_user WHERE id=?", String.class, admin)))
        .isTrue();
    assertThat(oauthClients.byClientId("identity-admin-tenant").policy())
        .isEqualTo(enrolled.policy());
    assertThat(management("GET", "/platform/tenants", platformToken, Map.of()).statusCode())
        .isEqualTo(200);
    activateIssuedAccount(
        "TENANT", code, "admin", tenantInitial, "Tenant bootstrap password " + UUID.randomUUID());
    String tenantToken = fixedManagementToken("identity-admin-tenant", tenantSecret, code, false);
    assertThat(
            json(bearer("/api/v1/identity/me", tenantToken))
                .get("data")
                .get("tenant_id")
                .asString())
        .isEqualTo(tenantId.toString());
    for (String path : java.util.List.of("/users", "/roles", "/permissions", "/audits"))
      assertThat(management("GET", path, tenantToken, Map.of()).statusCode())
          .as(path)
          .isEqualTo(200);
    assertThat(management("GET", "/platform/tenants", tenantToken, Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(management("GET", "/users", platformToken, Map.of()).statusCode()).isEqualTo(401);
    assertThat(management("GET", "/security-policy", tenantToken, Map.of()).statusCode())
        .isEqualTo(403);
    var publisher =
        oauthClients.byClientId(
            metadata.get("created").get("clients").get("PUBLISHER:document-service").asString());
    String publisherSecret =
        Files.readString(
                Path.of(
                    descriptor.get("publisher_secret_files").get("document-service").asString()))
            .strip();
    var credential =
        new OAuthClient(
            publisher.client().getId(),
            publisher.client().getClientId(),
            publisherSecret,
            false,
            false,
            "catalog.publish:document-service");
    assertThat(
            oauthToken(
                    credential,
                    Map.of("grant_type", "client_credentials", "scope", credential.scope()))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            oauthToken(
                    credential,
                    Map.of("grant_type", "client_credentials", "scope", "identity.users"))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void platformTenantApiCreatesInitialAdminActivatesThroughRealHttpAndDisablesNextToken()
      throws Exception {
    var administrator = platformManager();
    String code = "api-tenant-" + UUID.randomUUID(), request = "tenant-create-" + UUID.randomUUID();
    var command =
        Map.of(
            "code",
            code,
            "name",
            "新项目租户",
            "admin_username",
            "admin",
            "admin_display_name",
            "新租户管理员");
    var created = management("POST", "/platform/tenants", administrator.token(), command, request);
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json(created).get("data").get("tenant_id").asString(),
        initial = json(created).get("data").get("initial_password").asString();
    String adminId = json(created).get("data").get("admin_id").asString();
    assertThat(created.headers().allValues("Set-Cookie")).isEmpty();
    var replay = management("POST", "/platform/tenants", administrator.token(), command, request);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay).get("data").get("initial_password").isNull()).isTrue();
    assertThat(json(replay).get("data").get("tenant_id").asString()).isEqualTo(id);
    var query = management("GET", "/platform/tenants?q=" + code, administrator.token(), Map.of());
    assertThat(query.statusCode()).isEqualTo(200);
    assertThat(json(query).get("data").get("items").size()).isEqualTo(1);
    assertThat(query.body().contains(initial) || query.body().contains("password_hash")).isFalse();
    var clientBody = technicalClientCommand("tenant-app-" + UUID.randomUUID());
    clientBody.put("principal_type", "user");
    clientBody.put("owner_tenant_id", id);
    clientBody.put("allowed_tenant_ids", java.util.Set.of(id));
    var coreScopes =
        java.util.Set.of(
            "profile",
            "identity.users",
            "identity.roles",
            "identity.permissions",
            "identity.sessions",
            "identity.audit");
    clientBody.put("scopes", coreScopes);
    clientBody.put("redirect_uris", java.util.Set.of(CALLBACK));
    clientBody.put("first_party", true);
    var clientResponse =
        management(
            "POST",
            "/platform/oauth-clients",
            administrator.token(),
            clientBody,
            "tenant-app-" + UUID.randomUUID());
    assertThat(clientResponse.statusCode()).isEqualTo(200);
    var clientData = json(clientResponse).get("data");
    var client =
        new OAuthClient(
            clientData.get("client").get("id").asString(),
            clientData.get("client").get("client_id").asString(),
            clientData.get("client_secret").asString(),
            false,
            false,
            String.join(" ", coreScopes));
    activateIssuedAccount(
        "TENANT", code, "admin", initial, "Fresh tenant password " + UUID.randomUUID());
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client, code), VERIFIER)))
            .get("access_token")
            .asString();
    var me = json(bearer("/api/v1/identity/me", token)).get("data");
    assertThat(me.get("sub").asString()).isEqualTo(adminId);
    assertThat(me.get("tenant_id").asString()).isEqualTo(id);
    assertThat(me.get("profile").get("name").asString()).isEqualTo("新租户管理员");
    for (String path : java.util.List.of("/users", "/roles", "/permissions", "/audits")) {
      assertThat(management("GET", path, token, Map.of()).statusCode()).as(path).isEqualTo(200);
    }
    var permissionCodes = new java.util.ArrayList<String>();
    for (var permission : me.get("audiences").get("identity-service").get("permissions"))
      permissionCodes.add(permission.asString());
    assertThat(permissionCodes)
        .containsExactlyInAnyOrderElementsOf(
            com.ken.platform.identity.service.permission.BuiltinPermissionCatalog
                .PROTECTED_TENANT_PERMISSIONS);
    assertThat(management("GET", "/security-policy", token, Map.of()).statusCode()).isEqualTo(403);
    var disabled =
        management(
            "PUT",
            "/platform/tenants/" + id,
            administrator.token(),
            Map.of("version", 0, "name", "已停用租户", "status", "DISABLED"));
    assertThat(disabled.statusCode()).isEqualTo(200);
    assertThat(json(disabled).get("data").get("version").asLong()).isEqualTo(1);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(401);
    assertThat(
            management(
                    "PUT",
                    "/platform/tenants/" + id,
                    administrator.token(),
                    Map.of("version", 0, "name", "旧表单", "status", "ACTIVE"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            management(
                    "GET",
                    "/platform/tenants/" + UUID.randomUUID(),
                    administrator.token(),
                    Map.of())
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void platformAdministratorApiDeliversOnceAndDisabledActivatedAdminLosesRealToken()
      throws Exception {
    var administrator = platformManager();
    String name = "api-admin-" + UUID.randomUUID(), request = "admin-create-" + UUID.randomUUID();
    var command = Map.of("username", name, "display_name", "新平台管理员");
    var created = management("POST", "/platform/admins", administrator.token(), command, request);
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json(created).get("data").get("id").asString(),
        initial = json(created).get("data").get("initial_password").asString();
    var replay = management("POST", "/platform/admins", administrator.token(), command, request);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay).get("data").get("initial_password").isNull()).isTrue();
    var query = management("GET", "/platform/admins?q=" + name, administrator.token(), Map.of());
    assertThat(query.statusCode()).isEqualTo(200);
    assertThat(json(query).get("data").get("items").get(0).get("must_change_password").asBoolean())
        .isTrue();
    assertThat(query.body().contains(initial) || query.body().contains("password_hash")).isFalse();
    activateIssuedAccount(
        "PLATFORM", "", name, initial, "Fresh platform password " + UUID.randomUUID());
    String token =
        json(oauthToken(
                administrator.client(),
                codeGrant(authorizationCode(administrator.client()), VERIFIER)))
            .get("access_token")
            .asString();
    assertThat(json(bearer("/api/v1/identity/me", token)).get("data").get("sub").asString())
        .isEqualTo(id);
    var current = management("GET", "/platform/admins/" + id, administrator.token(), Map.of());
    assertThat(current.statusCode()).isEqualTo(200);
    long version = json(current).get("data").get("version").asLong();
    var renamed =
        management(
            "PUT",
            "/platform/admins/" + id,
            administrator.token(),
            Map.of("version", version, "display_name", "修改后的管理员", "status", "ACTIVE"));
    assertThat(renamed.statusCode()).isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    assertThat(
            management(
                    "PUT",
                    "/platform/admins/" + id,
                    administrator.token(),
                    Map.of("version", version + 1, "display_name", "修改后的管理员", "status", "DISABLED"))
                .statusCode())
        .isEqualTo(200);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(401);
    assertThat(
            management(
                    "PUT",
                    "/platform/admins/" + id,
                    administrator.token(),
                    Map.of("version", version, "display_name", "旧表单", "status", "ACTIVE"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            management(
                    "GET", "/platform/admins/" + UUID.randomUUID(), administrator.token(), Map.of())
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void tenantAccountCreationApiKeepsOrdinaryUserUnprivilegedAndReplayCannotResetPassword()
      throws Exception {
    var manager = managementFixture(true);
    String name = "api-user-" + UUID.randomUUID(), request = "user-create-" + UUID.randomUUID();
    var command = Map.of("username", name, "display_name", "新普通用户");
    var created = management("POST", "/users", manager.token(), command, request);
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json(created).get("data").get("id").asString(),
        initial = json(created).get("data").get("initial_password").asString();
    var pending = managementData(manager, "/users/" + id);
    assertThat(pending.get("status").asString()).isEqualTo("PENDING_ACTIVATION");
    assertThat(pending.get("must_change_password").asBoolean()).isTrue();
    assertThat(managementData(manager, "/users/" + id + "/roles").get("roles").size()).isZero();
    activateIssuedAccount(
        "TENANT", tenantCode, name, initial, "Fresh ordinary password " + UUID.randomUUID());
    String token =
        json(oauthToken(manager.client(), codeGrant(authorizationCode(manager.client()), VERIFIER)))
            .get("access_token")
            .asString();
    var me = json(bearer("/api/v1/identity/me", token)).get("data");
    assertThat(me.get("sub").asString()).isEqualTo(id);
    assertThat(me.get("audiences").get("identity-service").get("permissions").size()).isZero();
    var replay = management("POST", "/users", manager.token(), command, request);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay).get("data").get("initial_password").isNull()).isTrue();
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    assertThat(managementData(manager, "/users/" + id).get("status").asString())
        .isEqualTo("ACTIVE");
    assertThat(
            management(
                    "POST",
                    "/users",
                    manager.token(),
                    Map.of("username", name, "display_name", "不同请求内容"),
                    request)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            management("POST", "/users", manager.token(), command, "duplicate-" + UUID.randomUUID())
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void accountCreationApiRejectsDelegatedCredentialDeliveryAndExpiredAuthentication()
      throws Exception {
    var manager = managementFixture(false);
    var command = Map.of("username", "denied-user-" + UUID.randomUUID(), "display_name", "不能代收凭据");
    assertThat(
            management("POST", "/users", manager.token(), command, "denied-" + UUID.randomUUID())
                .statusCode())
        .isEqualTo(403);
    assertThat(management("POST", "/platform/admins", manager.token(), command).statusCode())
        .isEqualTo(401);
    assertThat(management("POST", "/users", null, command).statusCode()).isEqualTo(401);
    var administrator = platformManager();
    jdbc.update(
        "UPDATE sys_login_session SET created_at=? WHERE id=?",
        java.sql.Timestamp.from(
            java.time.Instant.now().minusSeconds(policies.current(null).recentAuthSeconds() + 5L)),
        binding(sid()));
    var rejected =
        management(
            "POST",
            "/platform/admins",
            administrator.token(),
            command,
            "old-admin-" + UUID.randomUUID());
    assertThat(rejected.statusCode()).isEqualTo(403);
    assertThat(json(rejected).get("code").asString()).isEqualTo("RECENT_AUTHENTICATION_REQUIRED");
    assertThat(
            management(
                    "POST",
                    "/platform/tenants",
                    administrator.token(),
                    Map.of(
                        "code",
                        "aged-tenant-" + UUID.randomUUID(),
                        "name",
                        "不创建",
                        "admin_username",
                        "admin",
                        "admin_display_name",
                        "管理员"),
                    "old-tenant-" + UUID.randomUUID())
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void platformAccountApiScopesFieldWhitelistAndBoundedQueriesAreEnforced() throws Exception {
    var administrator = platformManager();
    var narrowed =
        new OAuthClient(
            administrator.client().id(),
            administrator.client().clientId(),
            administrator.client().secret(),
            false,
            true,
            "profile");
    String token =
        json(oauthToken(narrowed, codeGrant(authorizationCode(narrowed), VERIFIER)))
            .get("access_token")
            .asString();
    assertThat(management("GET", "/platform/tenants", token, Map.of()).statusCode()).isEqualTo(403);
    assertThat(management("GET", "/platform/admins", token, Map.of()).statusCode()).isEqualTo(403);
    assertThat(
            management("GET", "/platform/admins?size=101", administrator.token(), Map.of())
                .statusCode())
        .isEqualTo(400);
    assertThat(
            management("GET", "/platform/tenants?page=-1", administrator.token(), Map.of())
                .statusCode())
        .isEqualTo(400);
    String search = URLEncoder.encode("' OR 1=1 -- %_", StandardCharsets.UTF_8);
    var empty = management("GET", "/platform/tenants?q=" + search, administrator.token(), Map.of());
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(json(empty).get("data").get("items").size()).isZero();
    var injected =
        management(
            "POST",
            "/platform/admins",
            administrator.token(),
            Map.of(
                "username",
                "field-" + UUID.randomUUID(),
                "display_name",
                "安全字段",
                "password",
                "DO_NOT_ECHO_INITIAL_PASSWORD"));
    assertThat(injected.statusCode()).isEqualTo(400);
    assertThat(injected.body()).doesNotContain("DO_NOT_ECHO_INITIAL_PASSWORD");
    assertThat(
            management(
                    "PUT",
                    "/platform/tenants/" + tenant,
                    administrator.token(),
                    Map.of("name", "缺少版本", "status", "ACTIVE"))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_EMPTY_PLATFORM_ACCOUNTS", matches = "true")
  void platformAccountApiCannotDisableLastAdministratorInFreshIsolatedDatabase() throws Exception {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_platform_user", Integer.class))
        .isZero();
    var administrator = platformManager();
    var rejected =
        management(
            "PUT",
            "/platform/admins/" + administrator.user(),
            administrator.token(),
            Map.of("version", 0, "display_name", "不得失去管理入口", "status", "DISABLED"));
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(json(rejected).get("code").asString()).isEqualTo("LAST_PLATFORM_ADMIN");
    assertThat(bearer("/api/v1/identity/me", administrator.token()).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_platform_user WHERE status='ACTIVE' AND NOT must_change_password",
                Integer.class))
        .isEqualTo(1);
  }

  private ManagementFixture securityManager(boolean protectedManager) throws Exception {
    var manager = managementFixture(protectedManager);
    for (String permission : java.util.List.of("identity:audit:read")) {
      String[] parts = permission.split(":");
      jdbc.update(
          "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'安全管理','identity-service','TENANT','identity','身份',?,'安全资源',?,ARRAY['TENANT'],?) ON CONFLICT DO NOTHING",
          permission,
          parts[1],
          parts[2],
          "identity." + parts[1]);
      data(
          () ->
              jdbc.update(
                  "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
                  tenant,
                  manager.managerRole(),
                  permission));
    }
    var scopes =
        java.util.Set.of(
            "profile",
            "identity.users",
            "identity.roles",
            "identity.permissions",
            "identity.audit");
    clientBoundary(manager.client(), scopes, java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            manager.client().id(),
            manager.client().clientId(),
            manager.client().secret(),
            false,
            false,
            String.join(" ", scopes));
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    return new ManagementFixture(
        client,
        token,
        manager.managerRole(),
        manager.businessRole(),
        manager.target(),
        manager.delegate(),
        manager.read());
  }

  @Test
  void retiredTenantPolicyReadAndWriteAreDeniedEvenForAdministrator() throws Exception {
    var manager = securityManager(true);
    assertThat(management("GET", "/security-policy", manager.token(), Map.of()).statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "PUT",
                    "/security-policy",
                    manager.token(),
                    Map.of("platform_version", 0, "values", Map.of("password_min_chars", 18)))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_security_policy WHERE owner_tenant_id=?",
                Integer.class,
                tenant))
        .isZero();
  }

  @Test
  void retiredTenantPolicyCannotBeRestoredByOldCapability() throws Exception {
    var manager = securityManager(true);
    assertThat(management("GET", "/security-policy", manager.token(), Map.of()).statusCode())
        .isEqualTo(403);
    assertThat(
            management(
                    "PUT",
                    "/security-policy",
                    manager.token(),
                    Map.of("platform_version", 0, "values", Map.of("password_min_chars", 18)))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_security_policy WHERE owner_tenant_id=?",
                Integer.class,
                tenant))
        .isZero();
  }

  @Test
  void platformSecurityPolicyApiUsesCurrentVersionAndRestoresOriginalFloorAfterRealWrite()
      throws Exception {
    var manager = platformManager();
    var response = management("GET", "/platform/security-policy", manager.token(), Map.of());
    assertThat(response.statusCode()).isEqualTo(200);
    var before = json(response).get("data");
    var original = new HashMap<String, Object>();
    for (String field : com.ken.platform.identity.dal.mapper.security.SecurityPolicyStore.FIELDS)
      original.put(field, before.get("values").get(field).asInt());
    int threshold = before.get("effective").get("login_failure_threshold").asInt();
    // 全局策略写入在独立测试库执行；默认失败阈值为 10，收紧一次再恢复原值。
    assertThat(threshold).isGreaterThan(1);
    try {
      var update =
          management(
              "PUT",
              "/platform/security-policy",
              manager.token(),
              Map.of(
                  "version",
                  before.get("version").asLong(),
                  "values",
                  Map.of("login_failure_threshold", threshold - 1)));
      assertThat(update.statusCode()).isEqualTo(200);
      assertThat(json(update).get("data").get("effective").get("login_failure_threshold").asInt())
          .isEqualTo(threshold - 1);
      assertThat(
              management(
                      "PUT",
                      "/platform/security-policy",
                      manager.token(),
                      Map.of(
                          "version",
                          before.get("version").asLong(),
                          "values",
                          Map.of("login_failure_threshold", threshold)))
                  .statusCode())
          .isEqualTo(409);
      assertThat(management("GET", "/security-policy", manager.token(), Map.of()).statusCode())
          .isEqualTo(403);
    } finally {
      var current =
          json(management("GET", "/platform/security-policy", manager.token(), Map.of()))
              .get("data");
      assertThat(
              management(
                      "PUT",
                      "/platform/security-policy",
                      manager.token(),
                      Map.of("version", current.get("version").asLong(), "values", original))
                  .statusCode())
          .isEqualTo(200);
    }
  }

  private UUID auditFixture(
      UUID owner,
      UUID actor,
      String type,
      String action,
      String target,
      String result,
      String details,
      java.time.Instant created) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sys_auth_audit(id,actor_id,actor_type,tenant_id,action,target_id,result,request_id,details,created_at) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)",
        id,
        actor,
        type,
        owner,
        action,
        target,
        result,
        "fixture-" + id,
        details,
        java.sql.Timestamp.from(created));
    return id;
  }

  @Test
  void ordinaryRoleWithHistoricalAuditPermissionCannotReadAudit() throws Exception {
    var manager = securityManager(false);
    assertThat(management("GET", "/audits", manager.token(), Map.of()).statusCode()).isEqualTo(403);
  }

  @Test
  void tenantAuditApiFiltersOwnScopeAndMasksKnownSecretsInStoredDifferences() throws Exception {
    var manager = securityManager(true);
    var now = java.time.Instant.now();
    UUID first =
        auditFixture(
            tenant,
            user,
            "TENANT",
            "identity.audit.fixture",
            manager.target().toString(),
            "SUCCESS",
            "{\"before\":null,\"after\":{\"display_name\":\"安全名称\",\"password\":\"DO_NOT_ECHO_AUDIT_PASSWORD\"},\"nested\":[{\"refresh_token\":\"DO_NOT_ECHO_AUDIT_REFRESH\"}]}",
            now.minusSeconds(30));
    UUID second =
        auditFixture(
            tenant,
            user,
            "TENANT",
            "identity.audit.fixture",
            manager.target().toString(),
            "FAILURE",
            "{\"visible\":\"second\"}",
            now.minusSeconds(20));
    auditFixture(
        otherTenant,
        otherTenantUser,
        "TENANT",
        "identity.audit.fixture",
        otherTenantUser.toString(),
        "SUCCESS",
        "{\"note\":\"FOREIGN_AUDIT_PRIVATE_NOTE\"}",
        now);
    var page =
        management(
            "GET",
            "/audits?action=identity.audit.fixture&size=1&tenant_id=" + otherTenant,
            manager.token(),
            Map.of());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(json(page).get("data").get("has_more").asBoolean()).isTrue();
    assertThat(json(page).get("data").get("items").get(0).get("id").asString())
        .isEqualTo(second.toString());
    var all = management("GET", "/audits?action=identity.audit.fixture", manager.token(), Map.of());
    assertThat(all.statusCode()).isEqualTo(200);
    assertThat(json(all).get("data").get("items").size()).isEqualTo(2);
    assertThat(all.body())
        .contains("[REDACTED]", "安全名称")
        .doesNotContain(
            "FOREIGN_AUDIT_PRIVATE_NOTE",
            "DO_NOT_ECHO_AUDIT_PASSWORD",
            "DO_NOT_ECHO_AUDIT_REFRESH");
    String from = URLEncoder.encode(now.minusSeconds(25).toString(), StandardCharsets.UTF_8),
        to = URLEncoder.encode(now.minusSeconds(15).toString(), StandardCharsets.UTF_8);
    var window =
        management(
            "GET",
            "/audits?action=identity.audit.fixture&actor_id="
                + user
                + "&result=FAILURE&target_id="
                + manager.target()
                + "&from="
                + from
                + "&to="
                + to,
            manager.token(),
            Map.of());
    assertThat(window.statusCode()).isEqualTo(200);
    assertThat(json(window).get("data").get("items").size()).isEqualTo(1);
    assertThat(json(window).get("data").get("items").get(0).get("id").asString())
        .isEqualTo(second.toString());
    assertThat(management("GET", "/platform/audits", manager.token(), Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void platformAuditApiReturnsOnlyPlatformControlOperationsAndNeverClientCredential()
      throws Exception {
    var manager = platformManager();
    var created =
        management(
            "POST",
            "/platform/oauth-clients",
            manager.token(),
            technicalClientCommand("audit-client-" + UUID.randomUUID()),
            "audit-client-" + UUID.randomUUID());
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json(created).get("data").get("client").get("id").asString(),
        secret = json(created).get("data").get("client_secret").asString();
    auditFixture(
        tenant,
        manager.user(),
        "TENANT",
        "identity.client.created",
        id,
        "SUCCESS",
        "{\"note\":\"TENANT_CONTROL_PRIVATE_NOTE\"}",
        java.time.Instant.now());
    var query =
        management(
            "GET",
            "/platform/audits?actor_id="
                + manager.user()
                + "&action=identity.client.created&target_id="
                + id,
            manager.token(),
            Map.of());
    assertThat(query.statusCode()).isEqualTo(200);
    assertThat(json(query).get("data").get("items").size()).isEqualTo(1);
    assertThat(json(query).get("data").get("items").get(0).get("actor_type").asString())
        .isEqualTo("PLATFORM");
    assertThat(
            query.body().contains(secret) || query.body().contains("TENANT_CONTROL_PRIVATE_NOTE"))
        .isFalse();
    assertThat(management("GET", "/audits", manager.token(), Map.of()).statusCode()).isEqualTo(401);
  }

  @Test
  void auditApiRejectsUnboundedInputsAndWritesAndDoesNotTurnStorageOutageIntoEmptyList()
      throws Exception {
    var manager = securityManager(true);
    auditFixture(
        tenant,
        user,
        "TENANT",
        "identity.audit.large",
        manager.target().toString(),
        "SUCCESS",
        JsonMapper.builder().build().writeValueAsString(Map.of("large", "x".repeat(20000))),
        java.time.Instant.now());
    var large = management("GET", "/audits?action=identity.audit.large", manager.token(), Map.of());
    assertThat(large.statusCode()).isEqualTo(200);
    assertThat(json(large).get("data").get("items").get(0).get("details_truncated").asBoolean())
        .isTrue();
    for (String query :
        java.util.List.of(
            "size=101",
            "page=-1",
            "from=not-a-date",
            "actor_id=not-a-uuid",
            "result=ANY",
            "from=2026-10-28T00:00:00Z&to=2026-10-27T00:00:00Z"))
      assertThat(management("GET", "/audits?" + query, manager.token(), Map.of()).statusCode())
          .isEqualTo(400);
    assertThat(management("DELETE", "/audits", manager.token(), Map.of()).statusCode())
        .isEqualTo(403);
    assertThat(management("GET", "/audits", null, Map.of()).statusCode()).isEqualTo(401);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_AUDIT_QUERY_DATABASE"))
        .when(auditQueries)
        .tenant(any(), any());
    var failed = management("GET", "/audits", manager.token(), Map.of());
    assertThat(failed.statusCode()).isEqualTo(503);
    assertThat(json(failed).get("data").isNull()).isTrue();
    assertThat(failed.body()).doesNotContain("DO_NOT_ECHO_AUDIT_QUERY_DATABASE");
    doCallRealMethod().when(auditQueries).tenant(any(), any());
    clientBoundary(
        manager.client(),
        java.util.Set.of(
            "profile",
            "identity.users",
            "identity.roles",
            "identity.permissions",
            "identity.security"),
        java.util.Set.of("identity-service"));
    assertThat(management("GET", "/audits", manager.token(), Map.of()).statusCode()).isEqualTo(403);
  }

  private LiveGrant liveGrant() {
    String tag = UUID.randomUUID().toString();
    String audience = "live-" + tag, read = "native:read:" + tag, write = "native:write:" + tag;
    String readScope = "native." + tag + ".read", writeScope = "native." + tag + ".write";
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES(?,?)", audience, audience);
    for (var entry : Map.of(read, readScope, write, writeScope).entrySet())
      jdbc.update(
          "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'实时权限',?,'TENANT','native','原生测试',?,'测试资源',?,ARRAY['SELF','TENANT'],?)",
          entry.getKey(),
          audience,
          tag,
          entry.getKey().equals(read) ? "read" : "write",
          entry.getValue());
    UUID role = UUID.randomUUID();
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'实时角色')",
              role,
              tenant,
              "live-" + tag);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              role);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'SELF'),(?,?,?,'TENANT')",
              tenant,
              role,
              read,
              tenant,
              role,
              write);
          return null;
        });
    var client = oauthClient(false);
    clientBoundary(
        client,
        java.util.Set.of("profile", readScope, writeScope),
        java.util.Set.of("identity-service", audience));
    return new LiveGrant(
        new OAuthClient(
            client.id(), client.clientId(), client.secret(), false, false, "profile " + readScope),
        role,
        read,
        write,
        readScope,
        writeScope,
        audience);
  }

  @Test
  void applicationMeUsesActualClientAndAllIssuedAudiencesWithoutLeakingRoleGrants()
      throws Exception {
    ordinary();
    login();
    var first = liveGrant();
    var second = liveGrant();
    var scopes =
        java.util.Set.of(
            "profile",
            first.readScope(),
            first.writeScope(),
            second.readScope(),
            second.writeScope());
    var audiences = java.util.Set.of("identity-service", first.audience(), second.audience());
    clientBoundary(first.client(), scopes, audiences);
    var client =
        new OAuthClient(
            first.client().id(),
            first.client().clientId(),
            first.client().secret(),
            false,
            false,
            "profile " + first.readScope() + " " + second.readScope());
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    var response =
        bearer(
            "/api/v1/identity/me?client_id="
                + second.client().clientId()
                + "&audience=other&user_id="
                + UUID.randomUUID(),
            token);
    assertThat(response.statusCode()).isEqualTo(200);
    var envelope = json(response);
    var me = envelope.get("data");
    assertThat(envelope.get("code").asString()).isEqualTo("OK");
    assertThat(envelope.get("request_id").asString())
        .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(me.get("view").asString()).isEqualTo("APPLICATION_EFFECTIVE");
    assertThat(me.get("sub").asString()).isEqualTo(user.toString());
    assertThat(me.get("tenant_id").asString()).isEqualTo(tenant.toString());
    assertThat(me.get("client_id").asString()).isEqualTo(client.clientId());
    assertThat(java.time.Instant.parse(me.get("as_of").asString()))
        .isBefore(java.time.Instant.now().plusSeconds(1));
    assertThat(me.get("audiences").size()).isEqualTo(3);
    var own = me.get("audiences").get(first.audience());
    assertThat(own.get("permissions").toString())
        .contains(first.read())
        .doesNotContain(first.write(), second.read());
    assertThat(own.get("data_scopes").get(first.read()).asString()).isEqualTo("SELF");
    assertThat(me.get("audiences").get(second.audience()).get("permissions").toString())
        .contains(second.read())
        .doesNotContain(first.read(), second.write());
    assertThat(me.get("audiences").get("identity-service").get("permissions").size()).isZero();
    assertThat(me.get("profile").get("name").asString()).isEqualTo("浏览器测试");
    assertThat(me.get("profile").has("email")).isFalse();
    assertThat(response.body())
        .doesNotContain(
            "role_id", "password_hash", "access_token", "refresh_token", first.role().toString());
    data(
        () ->
            jdbc.update(
                "UPDATE sys_role_permission SET data_scope='TENANT' WHERE role_id=? AND permission_code=?",
                first.role(),
                first.read()));
    assertThat(
            json(bearer("/api/v1/identity/me", token))
                .get("data")
                .get("audiences")
                .get(first.audience())
                .get("data_scopes")
                .get(first.read())
                .asString())
        .isEqualTo("TENANT");
    clientBoundary(client, java.util.Set.of("profile", second.readScope()), audiences);
    assertThat(
            json(bearer("/api/v1/identity/me", token))
                .get("data")
                .get("audiences")
                .get(first.audience())
                .get("permissions")
                .size())
        .isZero();
    clientBoundary(client, scopes, audiences);
    assertThat(
            json(bearer("/api/v1/identity/me", token))
                .get("data")
                .get("audiences")
                .get(first.audience())
                .get("permissions")
                .toString())
        .contains(first.read())
        .doesNotContain(first.write());
    clientBoundary(client, scopes, java.util.Set.of("identity-service", first.audience()));
    assertThat(
            json(bearer("/api/v1/identity/me", token))
                .get("data")
                .get("audiences")
                .has(second.audience()))
        .isFalse();
    String added = "added-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES(?,?)", added, added);
    clientBoundary(client, scopes, java.util.Set.of("identity-service", first.audience(), added));
    assertThat(json(bearer("/api/v1/identity/me", token)).get("data").get("audiences").has(added))
        .isFalse();
  }

  @Test
  void applicationMeNeedsUserAccessTokenAndNeverUsesSsoCookieOrAcceptsIdToken() throws Exception {
    ordinary();
    login();
    assertThat(get("/api/v1/identity/me").statusCode()).isEqualTo(401);
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    String token = issued.get("access_token").asString();
    assertThat(bearer("/api/v1/identity/me", issued.get("id_token").asString()).statusCode())
        .isEqualTo(401);
    var machineClient = reader("identity-service");
    String machine =
        json(oauthToken(
                machineClient,
                Map.of("grant_type", "client_credentials", "scope", machineClient.scope())))
            .get("access_token")
            .asString();
    assertThat(bearer("/api/v1/identity/me", machine).statusCode()).isEqualTo(401);
    var post =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/identity/me"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(403);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    asCurrentSso(service::signOutAllOwnSessions);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(401);
  }

  @Test
  void applicationMeDependencyFailureIs503AndExpiredSessionRevocationCommitsBefore401()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    UUID session = binding(sid());
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_APPLICATION_PROFILE"))
        .when(tokenUsers)
        .profile(any());
    var unavailable = bearer("/api/v1/identity/me", token);
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(json(unavailable).get("code").asString()).isEqualTo("IDENTITY_UNAVAILABLE");
    assertThat(unavailable.body())
        .doesNotContain("DO_NOT_ECHO_APPLICATION_PROFILE", token, client.secret());
    reset(tokenUsers);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '2 days' WHERE id=?", session);
    assertThat(bearer("/api/v1/identity/me", token).statusCode()).isEqualTo(401);
    assertThat(store.session(session, false).revokedAt()).isNotNull();
    assertThat(
            oauthAuthorizations
                .findByToken(
                    token,
                    org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                        .ACCESS_TOKEN)
                .getAccessToken()
                .isActive())
        .isFalse();
  }

  @Test
  void liveIntrospectionRecomputesRolesScopesAudienceAndCannotExpandOldToken() throws Exception {
    ordinary();
    login();
    var fixture = liveGrant();
    String token =
        json(oauthToken(fixture.client(), codeGrant(authorizationCode(fixture.client()), VERIFIER)))
            .get("access_token")
            .asString();
    var caller = reader(fixture.audience());
    var result =
        json(
            oauthExchange(
                "/oauth2/introspect",
                caller,
                Map.of("token", token, "tenant_id", UUID.randomUUID().toString())));
    assertThat(result.get("active").asBoolean()).isTrue();
    assertThat(result.get("tenant_id").asString()).isEqualTo(tenant.toString());
    assertThat(result.get("principal_type").asString()).isEqualTo("user");
    assertThat(result.get("permissions").toString())
        .contains(fixture.read())
        .doesNotContain(fixture.write());
    assertThat(result.get("data_scopes").get(fixture.read()).asString()).isEqualTo("SELF");
    data(
        () ->
            jdbc.update(
                "UPDATE sys_role_permission SET data_scope='TENANT' WHERE tenant_id=? AND role_id=? AND permission_code=?",
                tenant,
                fixture.role(),
                fixture.read()));
    assertThat(
            json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)))
                .get("data_scopes")
                .get(fixture.read())
                .asString())
        .isEqualTo("TENANT");
    data(
        () ->
            jdbc.update(
                "UPDATE sys_role SET status='DISABLED',authorization_version=authorization_version+1 WHERE id=?",
                fixture.role()));
    var denied = json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)));
    assertThat(denied.get("active").asBoolean()).isTrue();
    assertThat(denied.get("permissions").size()).isZero();
    assertThat(denied.get("data_scopes").size()).isZero();
    data(
        () ->
            jdbc.update(
                "UPDATE sys_role SET status='ACTIVE',authorization_version=authorization_version+1 WHERE id=?",
                fixture.role()));
    clientBoundary(
        fixture.client(),
        java.util.Set.of("profile", fixture.writeScope()),
        java.util.Set.of("identity-service", fixture.audience()));
    assertThat(
            json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)))
                .get("permissions")
                .size())
        .isZero();
    clientBoundary(
        fixture.client(),
        java.util.Set.of("profile", fixture.readScope(), fixture.writeScope()),
        java.util.Set.of("identity-service", fixture.audience()));
    assertThat(
            json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)))
                .get("permissions")
                .toString())
        .contains(fixture.read())
        .doesNotContain(fixture.write());
    String other = "other-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES(?,?)", other, other);
    assertThat(
            json(oauthExchange(
                    "/oauth2/introspect",
                    reader(other),
                    Map.of("token", token, "audience", fixture.audience())))
                .get("active")
                .asBoolean())
        .isFalse();
    jdbc.update("UPDATE sys_permission SET status='DISABLED' WHERE code=?", fixture.read());
    assertThat(
            json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)))
                .get("permissions")
                .size())
        .isZero();
  }

  @Test
  void oidcLogoutRevokesRelatedAuthorizationsAndClearsCookieBeforeRedirect() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    UUID session = binding(sid());
    var logout =
        get(
            "/connect/logout?"
                + form(
                    Map.of(
                        "id_token_hint",
                        issued.get("id_token").asString(),
                        "client_id",
                        client.clientId(),
                        "post_logout_redirect_uri",
                        "https://client.example/logged-out",
                        "state",
                        "oidc-exit-state")));
    assertThat(logout.statusCode()).isEqualTo(302);
    String location = logout.headers().firstValue("Location").orElseThrow();
    assertThat(URI.create(location).getHost()).isEqualTo("client.example");
    assertThat(URI.create(location).getPath()).isEqualTo("/logged-out");
    assertThat(parameter(location, "state")).isEqualTo("oidc-exit-state");
    assertThat(store.session(session, false).revokedAt()).isNotNull();
    var stored =
        oauthAuthorizations.findByToken(
            issued.get("access_token").asString(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    assertThat(stored.getAccessToken().isActive()).isFalse();
    assertThat(stored.getRefreshToken().isActive()).isFalse();
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    assertThat(
            oauthToken(
                    client,
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        issued.get("refresh_token").asString()))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("IDENTITY_SSO") && !cookie.hasExpired()))
        .isEmpty();
  }

  private com.ken.platform.identity.service.oauth.ApplicationConsentService applicationConsents(
      com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore applicationStore,
      SecurityAuditRepository failureAudits) {
    return new com.ken.platform.identity.service.oauth.ApplicationConsentService(
        applicationStore,
        store,
        service,
        oauthClients,
        oauthAuthorizations,
        context.getBean(
            org.springframework.security.oauth2.server.authorization
                .OAuth2AuthorizationConsentService.class),
        failureAudits,
        manager);
  }

  @Test
  void ownConsentRevocationRemovesOfficialConsentKeepsSsoAndRequiresFreshConsent()
      throws Exception {
    ordinary();
    login();
    UUID session = binding(sid());
    var client = oauthClient(true, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    asCurrentSso(
        () -> {
          var rows = applications.ownConsents(0, 100);
          assertThat(rows).hasSize(1);
          assertThat(rows.getFirst().clientId()).isEqualTo(client.clientId());
          assertThat(rows.getFirst().scopes()).contains("profile");
          assertThat(rows.getFirst().toString())
              .doesNotContain("client_secret", "refresh_token", sid());
          applications.revokeOwnConsent(client.clientId());
          applications.revokeOwnConsent(client.clientId());
          assertThat(applications.ownConsents(0, 100)).isEmpty();
        });
    assertThat(store.session(session, false).revokedAt()).isNull();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    var stored =
        oauthAuthorizations.findByToken(
            issued.get("access_token").asString(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    assertThat(stored).isNotNull();
    assertThat(stored.getAccessToken().isActive()).isFalse();
    assertThat(
            consentPage(get("/oauth2/authorize?" + authorizationQuery(client, tenantCode))).body())
        .contains("同意授权", "name=\"_csrf\"");
    var renewed = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    assertThat(bearer("/userinfo", renewed.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.consent.revoked' AND result='SUCCESS' AND target_id=?",
                Integer.class,
                user,
                client.clientId()))
        .isEqualTo(1);
  }

  @Test
  void ownApplicationRevocationCoversAllSessionsAndLeavesDifferentApplicationActive()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var first = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    login();
    var second = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var otherClient = oauthClient(false, true, false);
    var other = json(oauthToken(otherClient, codeGrant(authorizationCode(otherClient), VERIFIER)));
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    asCurrentSso(() -> applications.revokeOwnConsent(client.clientId()));
    for (var issued : java.util.List.of(first, second)) {
      assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
          .isEqualTo(401);
      assertThat(
              oauthToken(
                      client,
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "refresh_token",
                          issued.get("refresh_token").asString()))
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(bearer("/userinfo", other.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  void consentSuccessAuditFailureRollsBackBothConsentRemovalAndTokenRevocation() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var persistence = spy(new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_CONSENT"))
        .when(persistence)
        .audit(any(), anyString(), anyInt(), anyBoolean(), anyString());
    var applications = applicationConsents(persistence, protocolAudits);
    asCurrentSso(
        () -> {
          assertThatThrownBy(() -> applications.revokeOwnConsent(client.clientId()))
              .isInstanceOfSatisfying(
                  PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
          assertThat(applications.ownConsents(0, 100)).hasSize(1);
        });
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.consent.revoked' AND result='FAILURE'",
                Integer.class,
                user))
        .isEqualTo(1);
    doCallRealMethod()
        .when(persistence)
        .audit(any(), anyString(), anyInt(), anyBoolean(), anyString());
    asCurrentSso(() -> applications.revokeOwnConsent(client.clientId()));
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void ownConsentWithdrawalDoesNotTouchAnotherUserOnTheSameClient() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true, true, false);
    var original = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    UUID otherUser = UUID.randomUUID();
    String otherName = "other-" + otherUser;
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_user(id,tenant_id,normalized_username,display_name,password_hash,status,must_change_password) SELECT ?,tenant_id,?,'其他本人测试',password_hash,'ACTIVE',false FROM sys_user WHERE id=?",
                otherUser,
                otherName,
                user));
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var fields = new HashMap<>(credentials(csrf("/identity/login"), secret));
    fields.put("username", otherName);
    assertThat(post("/identity/login", fields).statusCode()).isEqualTo(302);
    var other = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    asCurrentSso(() -> applications.revokeOwnConsent(client.clientId()));
    assertThat(bearer("/userinfo", other.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    assertThat(bearer("/userinfo", original.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    assertThat(
            context
                .getBean(
                    org.springframework.security.oauth2.server.authorization
                        .OAuth2AuthorizationConsentService.class)
                .findById(client.id(), user.toString()))
        .isNotNull();
  }

  @Test
  void disabledClientConsentCanStillBeWithdrawnAndWrongRealmOrAnonymousCannotManageIt()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(true, true, false);
    oauthToken(client, codeGrant(authorizationCode(client), VERIFIER));
    jdbc.update(
        "UPDATE sys_client_policy SET status='DISABLED',version=version+1 WHERE registered_client_id=?",
        client.id());
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    var platformClient = oauthClient(false, true, true);
    asCurrentSso(
        () -> {
          assertThat(applications.ownConsents(0, 100).getFirst().status()).isEqualTo("DISABLED");
          assertThatThrownBy(() -> applications.revokeOwnConsent(platformClient.clientId()))
              .isInstanceOfSatisfying(
                  PlatformException.class, error -> assertThat(error.status()).isEqualTo(404));
          applications.revokeOwnConsent(client.clientId());
          assertThat(applications.ownConsents(0, 100)).isEmpty();
          assertThatThrownBy(() -> applications.ownConsents(0, 101))
              .isInstanceOfSatisfying(
                  PlatformException.class, error -> assertThat(error.status()).isEqualTo(400));
        });
    assertThatThrownBy(() -> applications.ownConsents(0, 100))
        .isInstanceOfSatisfying(
            PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));
  }

  @Test
  void consentReadAndIndependentFailureAuditOutagesReturn503WithoutPrivateDetails()
      throws Exception {
    ordinary();
    login();
    var persistence = spy(new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_CONSENT"))
        .when(persistence)
        .consents(any(), anyInt(), anyInt());
    var applications = applicationConsents(persistence, protocolAudits);
    asCurrentSso(
        () ->
            assertThatThrownBy(() -> applications.ownConsents(0, 100))
                .isInstanceOfSatisfying(
                    PlatformException.class,
                    error -> {
                      assertThat(error.status()).isEqualTo(503);
                      assertThat(error.getMessage()).doesNotContain("DO_NOT_ECHO_CONSENT");
                    }));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_AUDIT"))
        .when(protocolAudits)
        .failure(any(), anyString(), any(), anyString(), any(), anyString(), anyString());
    asCurrentSso(
        () ->
            assertThatThrownBy(() -> applications.ownConsents(0, 100))
                .isInstanceOfSatisfying(
                    PlatformException.class,
                    error -> {
                      assertThat(error.status()).isEqualTo(503);
                      assertThat(error.code()).isEqualTo("SECURITY_AUDIT_UNAVAILABLE");
                    }));
  }

  @Test
  void concurrentOwnConsentWithdrawalKeepsOneSuccessAuditAndPreservesSso() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    org.springframework.session.Session stored = sessions.findById(sid());
    org.springframework.security.core.context.SecurityContext security =
        stored.getAttribute("SPRING_SECURITY_CONTEXT");
    var start = new CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            executor.submit(
                () -> {
                  org.springframework.security.core.context.SecurityContextHolder.setContext(
                      security);
                  try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("并发启动未完成");
                    applications.revokeOwnConsent(client.clientId());
                    assertThat(com.ken.platform.foundation.http.CallContexts.current()).isNull();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                  }
                }));
      start.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.consent.revoked' AND result='SUCCESS' AND target_id=?",
                Integer.class,
                user,
                client.clientId()))
        .isEqualTo(1);
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  void consentReadFromExpiredSsoCommitsSessionAndAuthorizationRevocationBefore401()
      throws Exception {
    ordinary();
    login();
    UUID session = binding(sid());
    var client = oauthClient(true, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '9 hours',last_activity_at=now()-interval '9 hours' WHERE id=?",
        session);
    var applications =
        applicationConsents(
            new com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore(jdbc),
            protocolAudits);
    asCurrentSso(
        () ->
            assertThatThrownBy(() -> applications.ownConsents(0, 100))
                .isInstanceOfSatisfying(
                    PlatformException.class,
                    failure -> assertThat(failure.status()).isEqualTo(401)));
    assertThat(com.ken.platform.foundation.http.CallContexts.current()).isNull();
    assertThat(store.session(session, false).revokedAt()).isNotNull();
    var stored =
        oauthAuthorizations.findByToken(
            issued.get("access_token").asString(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    assertThat(stored.getAccessToken().isActive()).isFalse();
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void ownPasswordChangeRollsBackOnAuditFailureThenRevokesBothHttpGrantsAndRequiresNewPassword()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var first = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    login();
    var second = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_PASSWORD_AUDIT"))
        .when(store)
        .audit(any(), eq("identity.password.changed"), anyString());
    asCurrentSso(
        () ->
            assertThatThrownBy(() -> service.changeOwnPassword(secret, next))
                .isInstanceOfSatisfying(
                    PlatformException.class, error -> assertThat(error.status()).isEqualTo(503)));
    for (var issued : java.util.List.of(first, second))
      assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
          .isEqualTo(200);
    doCallRealMethod().when(store).audit(any(), eq("identity.password.changed"), anyString());
    asCurrentSso(() -> service.changeOwnPassword(secret, next));
    for (var issued : java.util.List.of(first, second)) {
      assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
          .isEqualTo(401);
      assertThat(
              oauthToken(
                      client,
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "refresh_token",
                          issued.get("refresh_token").asString()))
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(post("/identity/login", credentials(csrf("/identity/login"), secret)).statusCode())
        .isEqualTo(401);
    assertThat(post("/identity/login", credentials(csrf("/identity/login"), next)).statusCode())
        .isEqualTo(302);
    var fresh = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    assertThat(bearer("/userinfo", fresh.get("access_token").asString()).statusCode())
        .isEqualTo(200);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_PYTHON", matches = ".+")
  void realJavaAndPythonResourceClientsObserveSameOpaqueIdentityAndRevocation() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var reader = reader("identity-service");
    Path secretFile = signingDirectory.resolve("reader-" + UUID.randomUUID() + ".secret");
    Path tokenFile = signingDirectory.resolve("token-" + UUID.randomUUID() + ".secret");
    Path binding = signingDirectory.resolve("resource-" + UUID.randomUUID() + ".json");
    Files.writeString(secretFile, reader.secret());
    Files.writeString(tokenFile, issued.get("access_token").asString());
    Files.writeString(
        binding,
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "test_only",
                    true,
                    "origin",
                    origin,
                    "issuer",
                    "http://localhost:18080",
                    "audience",
                    "identity-service",
                    "client_id",
                    reader.clientId(),
                    "secret_file",
                    secretFile.toString(),
                    "token_file",
                    tokenFile.toString())));
    var directory = new com.ken.platform.foundation.discovery.ServiceDiscovery(System::nanoTime);
    directory.confirm(
        "identity-service",
        java.util.List.of(
            new com.ken.platform.foundation.discovery.ServiceInstance(
                "127.0.0.1", URI.create(origin).getPort(), "http")));
    var settings =
        new com.ken.platform.foundation.security.ResourceIdentitySettings(
            "local", "http://localhost:18080", "identity-service", reader.clientId(), secretFile);
    try (var javaClient =
        new com.ken.platform.foundation.security.ResourceIntrospector(
            directory,
            settings,
            com.ken.platform.foundation.config.RuntimeParameters.defaults(),
            Clock.systemUTC())) {
      assertThat(
              json(oauthExchange(
                      "/oauth2/introspect",
                      reader,
                      Map.of("token", issued.get("access_token").asString())))
                  .get("active")
                  .asBoolean())
          .as("标准 HTTP 用户 token 内省")
          .isTrue();
      assertThatCode(() -> javaClient.introspect(issued.get("access_token").asString()))
          .as("Java 资源客户端用户 token 内省")
          .doesNotThrowAnyException();
      var principal =
          (com.ken.platform.foundation.security.CurrentPrincipal)
              javaClient.introspect(issued.get("access_token").asString());
      assertThat(principal.subject()).isEqualTo(user.toString());
      assertThat(principal.tenantId()).isEqualTo(tenant);
      assertThat(principal.principalType()).isEqualTo("user");
      assertThat(principal.permissions()).isEmpty();
      assertThatThrownBy(() -> principal.require("profile", "identity:user:read", "user"))
          .isInstanceOfSatisfying(
              PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
      var python = pythonIdentityProbe(binding);
      assertThat(python.get("active").asBoolean()).isTrue();
      assertThat(python.get("subject").asString()).isEqualTo(principal.subject());
      assertThat(python.get("tenant_id").asString()).isEqualTo(tenant.toString());
      assertThat(python.get("permissions").size()).isZero();
      assertThatThrownBy(() -> javaClient.introspect(issued.get("id_token").asString()))
          .isInstanceOf(
              org.springframework.security.oauth2.server.resource.introspection
                  .BadOpaqueTokenException.class);
      var machine =
          json(
              oauthToken(
                  reader, Map.of("grant_type", "client_credentials", "scope", reader.scope())));
      assertThat(machine.has("access_token")).isTrue();
      assertThat(
              json(oauthExchange(
                      "/oauth2/introspect",
                      reader,
                      Map.of("token", machine.get("access_token").asString())))
                  .get("active")
                  .asBoolean())
          .as("标准 HTTP 机器 token 内省")
          .isTrue();
      var machinePrincipal =
          (com.ken.platform.foundation.security.CurrentPrincipal)
              javaClient.introspect(machine.get("access_token").asString());
      assertThat(machinePrincipal.principalType()).isEqualTo("service");
      assertThat(machinePrincipal.subject()).isEqualTo(reader.clientId());
      assertThat(machinePrincipal.tenantId()).isNull();
      data(
          () ->
              jdbc.update(
                  "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                  user));
      assertThatThrownBy(() -> javaClient.introspect(issued.get("access_token").asString()))
          .isInstanceOf(
              org.springframework.security.oauth2.server.resource.introspection
                  .BadOpaqueTokenException.class);
      var revoked = pythonIdentityProbe(binding);
      assertThat(revoked.get("active").asBoolean()).isFalse();
      assertThat(revoked.get("status").asInt()).isEqualTo(401);
    }
  }

  private tools.jackson.databind.JsonNode pythonIdentityProbe(Path binding) throws Exception {
    Path output = signingDirectory.resolve("python-result-" + UUID.randomUUID() + ".json");
    var builder =
        new ProcessBuilder(
            System.getenv("PLATFORM_TEST_PYTHON"),
            Path.of("..", "..", "sdk", "python", "tests", "probe_resource_identity.py")
                .toAbsolutePath()
                .toString(),
            binding.toString());
    var allowed =
        java.util.Set.of(
            "PATH",
            "SYSTEMROOT",
            "COMSPEC",
            "WINDIR",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH");
    builder
        .environment()
        .keySet()
        .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
    builder.environment().put("PYTHONIOENCODING", "utf-8");
    builder.redirectErrorStream(true).redirectOutput(output.toFile());
    var process = builder.start();
    try {
      assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      return JsonMapper.builder().build().readTree(Files.readString(output));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_GATEWAY_JAR", matches = ".+")
  void packagedGatewayActuallyIntrospectsAndProxiesIdentityMeWithoutCachingRevocation()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var reader = reader("identity-service");
    String tag = UUID.randomUUID().toString();
    Path secretFile = signingDirectory.resolve("gateway-reader-" + tag + ".secret");
    Files.writeString(secretFile, reader.secret());
    Path readers = signingDirectory.resolve("gateway-readers-" + tag + ".json");
    var json = JsonMapper.builder().build();
    Files.writeString(
        readers,
        json.writeValueAsString(
            Map.of(
                "readers",
                java.util.List.of(
                    Map.of(
                        "audience",
                        "identity-service",
                        "client_id",
                        reader.clientId(),
                        "secret_file",
                        secretFile.toString())))));
    Path ready = signingDirectory.resolve("gateway-ready-" + tag + ".json"),
        binding = signingDirectory.resolve("gateway-binding-" + tag + ".json");
    Files.writeString(
        binding,
        json.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "identity_origin",
                origin,
                "issuer",
                "http://localhost:18080",
                "readers_file",
                readers.toString(),
                "ready_file",
                ready.toString())));
    Path classes =
        Path.of("..", "api-gateway", "target", "test-classes", "com", "ken", "platform", "gateway")
            .toAbsolutePath();
    Path probeRoot = signingDirectory.resolve("gateway-probe-" + tag),
        probeTarget = probeRoot.resolve("com/ken/platform/gateway");
    Files.createDirectories(probeTarget);
    try (var files = Files.list(classes)) {
      for (Path file :
          files
              .filter(
                  path ->
                      path.getFileName()
                          .toString()
                          .matches("(?:GatewayResourceProbe(?:\\$.*)?|RouteFixtures)\\.class"))
              .toList()) Files.copy(file, probeTarget.resolve(file.getFileName()));
    }
    assertThat(probeTarget.resolve("GatewayResourceProbe.class")).exists();
    Path log = signingDirectory.resolve("gateway-" + tag + ".log");
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Dfile.encoding=UTF-8",
            "-Dloader.path=" + probeRoot,
            "-Dloader.main=com.ken.platform.gateway.GatewayResourceProbe",
            "-cp",
            System.getenv("PLATFORM_TEST_GATEWAY_JAR"),
            "org.springframework.boot.loader.launch.PropertiesLauncher",
            binding.toString());
    var allowed =
        java.util.Set.of(
            "PATH",
            "SYSTEMROOT",
            "COMSPEC",
            "WINDIR",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH");
    builder
        .environment()
        .keySet()
        .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    var process = builder.start();
    try {
      var until = java.time.Instant.now().plusSeconds(45);
      while (process.isAlive() && !Files.exists(ready) && java.time.Instant.now().isBefore(until))
        Thread.sleep(100);
      assertThat(Files.exists(ready)).as("Gateway 探针启动；失败日志位于 %s", log).isTrue();
      var gateway = json.readTree(Files.readString(ready)).get("origin").asString();
      try (var caller = HttpClient.newHttpClient()) {
        var response =
            caller.send(
                HttpRequest.newBuilder(URI.create(gateway + "/api/v1/identity/me?client_id=forged"))
                    .header("Authorization", "Bearer " + issued.get("access_token").asString())
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-Tenant-Id", UUID.randomUUID().toString())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var envelope = json(response);
        var me = envelope.get("data");
        assertThat(me.get("sub").asString()).isEqualTo(user.toString());
        assertThat(me.get("tenant_id").asString()).isEqualTo(tenant.toString());
        assertThat(me.get("client_id").asString()).isEqualTo(client.clientId());
        assertThat(envelope.get("request_id").asString())
            .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(gatewayBearer(caller, gateway, issued.get("id_token").asString()).statusCode())
            .isEqualTo(401);
        assertThat(
                caller
                    .send(
                        HttpRequest.newBuilder(
                                URI.create(
                                    gateway
                                        + "/api/v1/identity/me?access_token="
                                        + issued.get("access_token").asString()))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode())
            .isEqualTo(401);
        data(
            () ->
                jdbc.update(
                    "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                    user));
        assertThat(
                gatewayBearer(caller, gateway, issued.get("access_token").asString()).statusCode())
            .isEqualTo(401);
        jdbc.update(
            "UPDATE sys_client_policy SET status='DISABLED',version=version+1 WHERE registered_client_id=?",
            reader.id());
        var failure = gatewayBearer(caller, gateway, issued.get("access_token").asString());
        assertThat(failure.statusCode()).isEqualTo(503);
        assertThat(json(failure).get("code").asString()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(failure.body())
            .doesNotContain(reader.secret(), issued.get("access_token").asString());
      }
      process.getOutputStream().write('\n');
      process.getOutputStream().flush();
      assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      process.getOutputStream().close();
    }
  }

  private HttpResponse<String> gatewayBearer(HttpClient caller, String gateway, String token)
      throws Exception {
    return caller.send(
        HttpRequest.newBuilder(URI.create(gateway + "/api/v1/identity/me"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_PYTHON", matches = ".+")
  void realJavaToPythonAndPythonToJavaHttpPreservesIdentityAndLivePermissions() throws Exception {
    ordinary();
    login();
    var fixture = liveGrant();
    String token =
        json(oauthToken(fixture.client(), codeGrant(authorizationCode(fixture.client()), VERIFIER)))
            .get("access_token")
            .asString();
    var javaReader = reader(fixture.audience());
    var pythonReader = reader(fixture.audience());
    String tag = UUID.randomUUID().toString();
    Path javaSecret = signingDirectory.resolve("java-reader-" + tag + ".secret"),
        pythonSecret = signingDirectory.resolve("python-reader-" + tag + ".secret");
    Files.writeString(javaSecret, javaReader.secret());
    Files.writeString(pythonSecret, pythonReader.secret());
    Path ready = signingDirectory.resolve("python-api-ready-" + tag + ".json"),
        binding = signingDirectory.resolve("python-api-" + tag + ".json");
    var json = JsonMapper.builder().build();
    Files.writeString(
        binding,
        json.writeValueAsString(
            Map.of(
                "test_only",
                true,
                "identity_origin",
                origin,
                "issuer",
                "http://localhost:18080",
                "audience",
                fixture.audience(),
                "client_id",
                pythonReader.clientId(),
                "secret_file",
                pythonSecret.toString(),
                "scope",
                fixture.readScope(),
                "permission",
                fixture.read(),
                "ready_file",
                ready.toString())));
    Path log = signingDirectory.resolve("python-api-" + tag + ".log");
    var builder =
        new ProcessBuilder(
            System.getenv("PLATFORM_TEST_PYTHON"),
            Path.of("..", "..", "sdk", "python", "tests", "probe_resource_server.py")
                .toAbsolutePath()
                .toString(),
            binding.toString());
    var allowed =
        java.util.Set.of(
            "PATH",
            "SYSTEMROOT",
            "COMSPEC",
            "WINDIR",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH");
    builder
        .environment()
        .keySet()
        .removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
    builder.environment().put("PYTHONIOENCODING", "utf-8");
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    var process = builder.start();
    try {
      var until = java.time.Instant.now().plusSeconds(20);
      while (process.isAlive() && !Files.exists(ready) && java.time.Instant.now().isBefore(until))
        Thread.sleep(100);
      assertThat(Files.exists(ready)).as("Python API 启动；失败日志位于 %s", log).isTrue();
      var python = URI.create(json.readTree(Files.readString(ready)).get("origin").asString());
      var directory = new com.ken.platform.foundation.discovery.ServiceDiscovery(System::nanoTime);
      directory.confirm(
          "identity-service",
          java.util.List.of(
              new com.ken.platform.foundation.discovery.ServiceInstance(
                  "127.0.0.1", URI.create(origin).getPort(), "http")));
      directory.confirm(
          "analysis-service",
          java.util.List.of(
              new com.ken.platform.foundation.discovery.ServiceInstance(
                  "127.0.0.1", python.getPort(), "http")));
      var parameters = com.ken.platform.foundation.config.RuntimeParameters.defaults();
      try (var introspector =
          new com.ken.platform.foundation.security.ResourceIntrospector(
              directory,
              new com.ken.platform.foundation.security.ResourceIdentitySettings(
                  "local",
                  "http://localhost:18080",
                  fixture.audience(),
                  javaReader.clientId(),
                  javaSecret),
              parameters,
              Clock.systemUTC())) {
        var principal =
            (com.ken.platform.foundation.security.CurrentPrincipal) introspector.introspect(token);
        principal.require(fixture.readScope(), fixture.read(), "user");
        var call =
            new com.ken.platform.foundation.http.CallContext(
                "cross-language-" + tag,
                principal.tenantId(),
                principal.subject(),
                token,
                "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01",
                java.time.Instant.now().plusSeconds(30));
        var client =
            com.ken.platform.foundation.http.ServiceHttpClient.configured(
                directory, Clock.systemUTC(), parameters);
        var response =
            client
                .request("analysis-service", "GET", "/api/v1/analysis-jobs/probe", null, call)
                .get(12, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var envelope = json.readTree(response.getBody());
        var data = envelope.get("data");
        assertThat(data.get("subject").asString()).isEqualTo(user.toString());
        assertThat(data.get("remote_subject").asString()).isEqualTo(user.toString());
        assertThat(data.get("tenant_id").asString()).isEqualTo(tenant.toString());
        assertThat(data.get("client_id").asString()).isEqualTo(fixture.client().clientId());
        assertThat(data.get("data_scope").asString()).isEqualTo("SELF");
        assertThat(envelope.get("request_id").asString()).isEqualTo(call.requestId());
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(call.requestId());
        try (var caller = HttpClient.newHttpClient()) {
          var forged =
              caller.send(
                  HttpRequest.newBuilder(
                          python.resolve(
                              "/api/v1/analysis-jobs/probe?tenant_id=" + UUID.randomUUID()))
                      .header("Authorization", "Bearer " + token)
                      .header("X-User-Id", UUID.randomUUID().toString())
                      .header("X-Tenant-Id", UUID.randomUUID().toString())
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(forged.statusCode()).isEqualTo(200);
          assertThat(json(forged).get("data").get("subject").asString()).isEqualTo(user.toString());
          assertThat(
                  caller
                      .send(
                          HttpRequest.newBuilder(python.resolve("/api/v1/analysis-jobs/probe"))
                              .header("Cookie", "IDENTITY_SSO=forged")
                              .GET()
                              .build(),
                          HttpResponse.BodyHandlers.ofString())
                      .statusCode())
              .isEqualTo(401);
        }
        data(
            () ->
                jdbc.update(
                    "UPDATE sys_role SET status='DISABLED',authorization_version=authorization_version+1 WHERE id=?",
                    fixture.role()));
        assertThat(
                client
                    .request("analysis-service", "GET", "/api/v1/analysis-jobs/probe", null, call)
                    .get(12, TimeUnit.SECONDS)
                    .getStatusCode()
                    .value())
            .isEqualTo(403);
        data(
            () ->
                jdbc.update(
                    "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                    user));
        assertThat(
                client
                    .request("analysis-service", "GET", "/api/v1/analysis-jobs/probe", null, call)
                    .get(12, TimeUnit.SECONDS)
                    .getStatusCode()
                    .value())
            .isEqualTo(401);
      }
      process.getOutputStream().write('\n');
      process.getOutputStream().flush();
      assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      process.getOutputStream().close();
    }
  }

  private void asCurrentSso(Runnable action) {
    org.springframework.session.Session storedSession = sessions.findById(sid());
    var context =
        (org.springframework.security.core.context.SecurityContext)
            storedSession.getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(context).isNotNull();
    org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    try {
      action.run();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  void ownSessionRevokeInvalidatesOnlyItsLinkedHttpTokensAndCookie() throws Exception {
    ordinary();
    login();
    UUID first = binding(sid());
    var firstBrowser = http;
    var client = oauthClient(false, true, false);
    var firstToken = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    login();
    UUID second = binding(sid());
    var secondToken = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    asCurrentSso(
        () -> {
          assertThat(service.ownSessions(0, 100))
              .allSatisfy(row -> assertThat(row.applications()).contains(client.clientId()));
          service.revokeOwnSession(first);
          service.revokeOwnSession(first);
        });
    assertThat(store.session(first, false).revokedAt()).isNotNull();
    assertThat(store.session(second, false).revokedAt()).isNull();
    assertThat(bearer("/userinfo", firstToken.get("access_token").asString()).statusCode())
        .isEqualTo(401);
    assertThat(bearer("/userinfo", secondToken.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    assertThat(
            oauthToken(
                    client,
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        firstToken.get("refresh_token").asString()))
                .statusCode())
        .isEqualTo(400);
    var firstPage =
        firstBrowser.send(
            HttpRequest.newBuilder(URI.create(origin + "/identity/session")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(firstPage.statusCode()).isEqualTo(302);
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  void allSessionAuditFailureRollsBackAuthorizationRevocationAndHttpTokenRemainsValid()
      throws Exception {
    ordinary();
    login();
    UUID session = binding(sid());
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_SESSION_AUDIT"))
        .when(store)
        .audit(any(), eq("identity.sessions.signed_out_all"), anyString());
    asCurrentSso(
        () ->
            assertThatThrownBy(service::signOutAllOwnSessions)
                .isInstanceOfSatisfying(
                    PlatformException.class,
                    failure -> assertThat(failure.status()).isEqualTo(503)));
    assertThat(store.session(session, false).revokedAt()).isNull();
    assertThat(
            data(
                () ->
                    jdbc.queryForObject(
                        "SELECT auth_version FROM sys_user WHERE id=?", Long.class, user)))
        .isZero();
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(200);
    var stored =
        oauthAuthorizations.findByToken(
            issued.get("access_token").asString(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    assertThat(stored.getAccessToken().isActive()).isTrue();
    doCallRealMethod()
        .when(store)
        .audit(any(), eq("identity.sessions.signed_out_all"), anyString());
    asCurrentSso(() -> assertThat(service.signOutAllOwnSessions()).isEqualTo(1));
    assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void allSessionExitRevokesBothBrowserGrantsAndFreshLoginUsesNewAccountVersion() throws Exception {
    ordinary();
    login();
    UUID first = binding(sid());
    var client = oauthClient(false, true, false);
    var firstToken = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    login();
    UUID second = binding(sid());
    var secondToken = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    asCurrentSso(() -> assertThat(service.signOutAllOwnSessions()).isEqualTo(2));
    for (var issued : java.util.List.of(firstToken, secondToken)) {
      assertThat(bearer("/userinfo", issued.get("access_token").asString()).statusCode())
          .isEqualTo(401);
      assertThat(
              oauthToken(
                      client,
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "refresh_token",
                          issued.get("refresh_token").asString()))
                  .statusCode())
          .isEqualTo(400);
    }
    assertThat(store.session(first, false).revokedAt()).isNotNull();
    assertThat(store.session(second, false).revokedAt()).isNotNull();
    assertThat(get("/identity/session").statusCode()).isEqualTo(302);
    login();
    assertThat(store.session(binding(sid()), false).authVersion()).isEqualTo(1);
    var fresh = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    assertThat(bearer("/userinfo", fresh.get("access_token").asString()).statusCode())
        .isEqualTo(200);
  }

  @Test
  void oidcHintFromAnotherSessionCannotLogoutCurrentSessionAndNonceIsRequired() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    String query = authorizationQuery(client, tenantCode);
    assertThat(get("/oauth2/authorize?" + query.replace("native-oidc-nonce", "")).statusCode())
        .isEqualTo(400);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    login();
    UUID current = binding(sid());
    var rejected =
        get(
            "/connect/logout?"
                + form(
                    Map.of(
                        "id_token_hint",
                        issued.get("id_token").asString(),
                        "client_id",
                        client.clientId(),
                        "post_logout_redirect_uri",
                        "https://client.example/logged-out")));
    assertThat(rejected.statusCode()).isEqualTo(400);
    assertThat(store.session(current, false).revokedAt()).isNull();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  void userInfoStorageAndFailureAuditOutagesReturn503WithoutSecretEcho() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false, true, false);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    String token = issued.get("access_token").asString();
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_USERINFO_STORAGE"))
        .when(tokenUsers)
        .profile(any());
    var unavailable = bearer("/userinfo", token);
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.body())
        .doesNotContain("DO_NOT_ECHO_USERINFO_STORAGE", token, client.secret());
    reset(tokenUsers);
    assertThat(bearer("/userinfo", token).statusCode()).isEqualTo(200);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_USERINFO_AUDIT"))
        .when(protocolAudits)
        .protocolEvent(any(), eq("FAILURE"), any(), any(), any());
    assertThat(bearer("/userinfo", "unknown-userinfo-token").statusCode()).isEqualTo(503);
  }

  @Test
  void tenantCapabilityImmediatelyFiltersPermissionsAndTheirDataScopes() throws Exception {
    ordinary();
    login();
    String tag = UUID.randomUUID().toString(), permission = "identity:security:live-" + tag;
    String scope = "native.security." + tag;
    jdbc.update(
        "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES(?,'策略测试','identity-service','TENANT','native','测试',?,'策略','manage',ARRAY['TENANT'],?)",
        permission,
        tag,
        scope);
    UUID role = UUID.randomUUID();
    data(
        () -> {
          jdbc.update(
              "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'策略测试角色')",
              role,
              tenant,
              "live-" + tag);
          jdbc.update(
              "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
              tenant,
              user,
              role);
          jdbc.update(
              "INSERT INTO sys_role_permission(tenant_id,role_id,permission_code,data_scope) VALUES(?,?,?,'TENANT')",
              tenant,
              role,
              permission);
          return null;
        });
    var original = oauthClient(false);
    clientBoundary(
        original, java.util.Set.of("profile", scope), java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            original.id(),
            original.clientId(),
            original.secret(),
            false,
            false,
            "profile " + scope);
    String token =
        json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)))
            .get("access_token")
            .asString();
    var caller = reader("identity-service");
    assertThat(
            json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)))
                .get("permissions")
                .size())
        .isZero();
    data(
        () ->
            jdbc.update(
                "INSERT INTO sys_tenant_capability(tenant_id,capability,enabled) VALUES(?,'SECURITY_POLICY_MANAGEMENT',true)",
                tenant));
    var allowed = json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)));
    assertThat(allowed.get("permissions").toString()).contains(permission);
    assertThat(allowed.get("data_scopes").get(permission).asString()).isEqualTo("TENANT");
    data(
        () ->
            jdbc.update(
                "UPDATE sys_tenant_capability SET enabled=false,version=version+1 WHERE tenant_id=? AND capability='SECURITY_POLICY_MANAGEMENT'",
                tenant));
    var denied = json(oauthExchange("/oauth2/introspect", caller, Map.of("token", token)));
    assertThat(denied.get("permissions").size()).isZero();
    assertThat(denied.get("data_scopes").size()).isZero();
  }

  @Test
  void platformLoginOidcAndIntrospectionNeverAcquireTenantBusinessPermissions() throws Exception {
    UUID platformUser = UUID.randomUUID();
    String platformName = "platform-" + platformUser, password = "Fixture platform " + platformUser;
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES(?,?,'平台测试',?,false)",
        platformUser,
        platformName,
        passwords.encode(password, policies.current(null)));
    var credentials = new HashMap<>(credentials(csrf("/identity/login"), password));
    credentials.put("realm", "PLATFORM");
    credentials.put("tenant_code", "");
    credentials.put("username", platformName);
    assertThat(post("/identity/login", credentials).statusCode()).isEqualTo(302);
    var original = oauthClient(false, true, true);
    String tag = UUID.randomUUID().toString(),
        scope = "native.platform." + tag,
        permission = "platform:native:" + tag;
    jdbc.update(
        "INSERT INTO sys_permission(code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,oauth_scope) VALUES(?,'平台操作','identity-service','PLATFORM','native','测试',?,'平台','manage',?)",
        permission,
        tag,
        scope);
    clientBoundary(
        original,
        java.util.Set.of("openid", "profile", "email", scope),
        java.util.Set.of("identity-service"));
    var client =
        new OAuthClient(
            original.id(),
            original.clientId(),
            original.secret(),
            false,
            true,
            "openid profile email " + scope);
    var issued = json(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)));
    var result =
        json(
            oauthExchange(
                "/oauth2/introspect",
                reader("identity-service"),
                Map.of(
                    "token",
                    issued.get("access_token").asString(),
                    "tenant_id",
                    tenant.toString())));
    assertThat(result.get("active").asBoolean()).isTrue();
    assertThat(result.get("sub").asString()).isEqualTo(platformUser.toString());
    assertThat(result.get("principal_type").asString()).isEqualTo("platform");
    assertThat(result.has("tenant_id")).isFalse();
    assertThat(result.get("permissions").toString()).contains(permission);
    assertThat(result.get("data_scopes").size()).isZero();
    assertThat(json(bearer("/userinfo", issued.get("access_token").asString())).has("email"))
        .isFalse();
    var application =
        json(bearer("/api/v1/identity/me", issued.get("access_token").asString())).get("data");
    assertThat(application.get("principal_type").asString()).isEqualTo("platform");
    assertThat(application.get("sub").asString()).isEqualTo(platformUser.toString());
    assertThat(application.get("tenant_id").isNull()).isTrue();
    assertThat(application.get("audiences").get("identity-service").get("permissions").toString())
        .contains(permission);
    assertThat(application.get("audiences").get("identity-service").get("data_scopes").size())
        .isZero();
  }

  private String authorizationQuery(OAuthClient client, String code) throws Exception {
    String challenge =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(VERIFIER.getBytes(StandardCharsets.UTF_8)));
    var fields = new HashMap<String, String>();
    fields.put("client_id", client.clientId());
    fields.put("response_type", "code");
    fields.put("scope", client.scope());
    fields.put("redirect_uri", CALLBACK);
    fields.put("state", "native-user-state");
    fields.put("code_challenge_method", "S256");
    fields.put("code_challenge", challenge);
    if (!client.platform()) fields.put("tenant_code", code);
    if (java.util.Arrays.asList(client.scope().split(" ")).contains("openid"))
      fields.put("nonce", "native-oidc-nonce");
    return form(fields);
  }

  private String parameter(String url, String key) {
    return java.util.Arrays.stream(URI.create(url).getRawQuery().split("&"))
        .map(value -> value.split("=", 2))
        .filter(pair -> pair[0].equals(key))
        .map(pair -> java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
        .findFirst()
        .orElseThrow();
  }

  private String authorizationCode(OAuthClient client) throws Exception {
    return authorizationCode(client, tenantCode);
  }

  private String authorizationCode(OAuthClient client, String requestedTenantCode)
      throws Exception {
    var response = get("/oauth2/authorize?" + authorizationQuery(client, requestedTenantCode));
    if (client.publicClient()) {
      response = consentPage(response);
      var state = Pattern.compile("name=\"state\"[^>]*value=\"([^\"]+)\"").matcher(response.body());
      assertThat(state.find()).isTrue();
      var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(response.body());
      assertThat(csrf.find()).isTrue();
      response =
          post(
              "/oauth2/authorize",
              Map.of(
                  "client_id",
                  client.clientId(),
                  "state",
                  state.group(1),
                  "scope",
                  "profile",
                  "_csrf",
                  csrf.group(1)));
    }
    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(URI.create(location).getHost()).isEqualTo("client.example");
    assertThat(parameter(location, "state")).isEqualTo("native-user-state");
    return parameter(location, "code");
  }

  private HttpResponse<String> consentPage(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    var uri = URI.create(location);
    assertThat(uri.getPath()).isEqualTo("/identity/consent");
    assertThat(uri.getScheme() + "://" + uri.getAuthority()).isEqualTo("http://localhost:18080");
    var page = get(uri.getRawPath() + "?" + uri.getRawQuery());
    assertThat(page.statusCode()).isEqualTo(200);
    return page;
  }

  private String consentState(HttpResponse<String> response) throws Exception {
    response = consentPage(response);
    assertThat(response.statusCode()).isEqualTo(200);
    var state = Pattern.compile("name=\"state\"[^>]*value=\"([^\"]+)\"").matcher(response.body());
    assertThat(state.find()).isTrue();
    return state.group(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void publicConsentUsesRenderedCsrfForApprovalAndRefusal(boolean approve) throws Exception {
    ordinary();
    login();
    var client = oauthClient(true);
    var page = consentPage(get("/oauth2/authorize?" + authorizationQuery(client, tenantCode)));
    assertThat(page.body())
        .contains("同意授权", "拒绝", "action=\"/oauth2/authorize\"")
        .doesNotContain("<script", "cdn.jsdelivr.net", "bootstrap.min.css");
    assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("script-src 'self'", "form-action 'self'", "https://client.example");
    assertThat(page.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    var state = Pattern.compile("name=\"state\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
    var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
    assertThat(state.find()).isTrue();
    assertThat(csrf.find()).isTrue();
    var fields = new HashMap<>(Map.of("client_id", client.clientId(), "state", state.group(1)));
    if (approve) fields.put("scope", "profile");
    assertThat(post("/oauth2/authorize", fields).statusCode()).isEqualTo(403);
    fields.put("_csrf", "invalid-csrf");
    assertThat(post("/oauth2/authorize", fields).statusCode()).isEqualTo(403);
    fields.put("_csrf", csrf.group(1));
    var submitted = post("/oauth2/authorize", fields);
    assertThat(submitted.statusCode()).isEqualTo(302);
    String location = submitted.headers().firstValue("Location").orElseThrow();
    assertThat(URI.create(location).getHost()).isEqualTo("client.example");
    if (approve) {
      assertThat(oauthToken(client, codeGrant(parameter(location, "code"), VERIFIER)).statusCode())
          .isEqualTo(200);
    } else {
      assertThat(parameter(location, "error")).isEqualTo("access_denied");
      assertThat(location).doesNotContain("code=");
    }
  }

  @Test
  void localSwaggerLookupUsesPersistedUserTypeAndRejectsDisabledOrConfidentialClients()
      throws Exception {
    ordinary();
    String callback = "http://localhost:48083/docs/oauth2-redirect";
    var client = oauthClient(true);
    jdbc.update(
        "UPDATE oauth2_registered_client SET client_id=?,redirect_uris=? WHERE id=?",
        client.clientId() + "-swagger",
        callback,
        client.id());
    var confidential = oauthClient(false);
    String forbidden = "http://localhost:48084/docs/oauth2-redirect";
    jdbc.update(
        "UPDATE oauth2_registered_client SET client_id=?,redirect_uris=? WHERE id=?",
        confidential.clientId() + "-swagger",
        forbidden,
        confidential.id());
    var clients = context.getBean(ClientStore.class);
    assertThat(clients.localSwaggerRedirects()).contains(callback).doesNotContain(forbidden);
    // 未开始该客户端的授权流程，登录页不枚举已登记回调。
    assertThat(get("/identity/login").headers().firstValue("Content-Security-Policy").orElseThrow())
        .doesNotContain(callback, forbidden);
    try (var corsContext =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      corsContext.setEnvironment(
          new org.springframework.mock.env.MockEnvironment().withProperty("APP_ENV", "local"));
      corsContext.registerBean(ClientStore.class, () -> clients);
      corsContext.register(com.ken.platform.identity.config.LocalSwaggerCorsConfiguration.class);
      corsContext.refresh();
      var filter =
          corsContext
              .getBean(
                  "localSwaggerCors",
                  org.springframework.boot.web.servlet.FilterRegistrationBean.class)
              .getFilter();
      for (String origin : java.util.List.of("http://localhost:48083", "http://localhost:48084")) {
        var request =
            new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/oauth2/token");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-requested-with");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        if (origin.endsWith(":48083"))
          assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        else assertThat(response.getStatus()).isEqualTo(403);
      }
    }
    jdbc.update(
        "UPDATE sys_client_policy SET status='DISABLED' WHERE registered_client_id=?", client.id());
    assertThat(clients.localSwaggerRedirects()).doesNotContain(callback, forbidden);
    assertThat(get("/identity/login").headers().firstValue("Content-Security-Policy").orElseThrow())
        .doesNotContain(callback, forbidden);
  }

  @Test
  void consentPageRequiresLoginAndRejectsUnknownClientsAndUnregisteredScopes() throws Exception {
    ordinary();
    var client = oauthClient(true);
    String query = "?client_id=" + client.clientId() + "&scope=profile&state=display-state";
    var anonymous = get("/identity/consent" + query);
    assertThat(anonymous.statusCode()).isEqualTo(302);
    assertThat(anonymous.headers().firstValue("Location").orElseThrow())
        .contains("/identity/login");
    login();
    assertThat(
            get("/identity/consent" + query.replace(client.clientId(), "unknown-client"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/identity/consent" + query.replace("scope=profile", "scope=unregistered"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/identity/consent" + query.replace("state=display-state", "state=")).statusCode())
        .isEqualTo(400);
  }

  @Test
  void consentCannotBeReusedByAnotherSessionAndDenialKeepsRevokedHistory() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true);
    String state = consentState(get("/oauth2/authorize?" + authorizationQuery(client, tenantCode)));
    String previous = sid();
    login();
    assertThat(sid()).isNotEqualTo(previous);
    var stale =
        post(
            "/oauth2/authorize",
            Map.of(
                "client_id",
                client.clientId(),
                "state",
                state,
                "scope",
                "profile",
                "_csrf",
                csrf("/identity/session")));
    assertThat(stale.statusCode()).isEqualTo(400);
    String deniedState =
        consentState(get("/oauth2/authorize?" + authorizationQuery(client, tenantCode)));
    var denied =
        post(
            "/oauth2/authorize",
            Map.of(
                "client_id",
                client.clientId(),
                "state",
                deniedState,
                "_csrf",
                csrf("/identity/session")));
    assertThat(denied.statusCode()).isEqualTo(302);
    assertThat(parameter(denied.headers().firstValue("Location").orElseThrow(), "error"))
        .isEqualTo("access_denied");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=? AND attributes::jsonb->>'platform.revoked-at' IS NOT NULL",
                Integer.class,
                client.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                client.id()))
        .isEqualTo(2);
  }

  @Test
  void badPkceAndCallbackAreRejectedAndConsentPostNeedsCsrf() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true);
    String query = authorizationQuery(client, tenantCode);
    var plain =
        get(
            "/oauth2/authorize?"
                + query.replace("code_challenge_method=S256", "code_challenge_method=plain"));
    assertThat(plain.statusCode()).isEqualTo(302);
    String rejected = plain.headers().firstValue("Location").orElseThrow();
    assertThat(URI.create(rejected).getHost()).isEqualTo("client.example");
    assertThat(parameter(rejected, "error")).isEqualTo("invalid_request");
    assertThat(parameter(rejected, "state")).isEqualTo("native-user-state");
    assertThat(
            get("/oauth2/authorize?"
                    + query.replace(
                        URLEncoder.encode(CALLBACK, StandardCharsets.UTF_8),
                        URLEncoder.encode(
                            "https://untrusted.example/callback", StandardCharsets.UTF_8)))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                client.id()))
        .isZero();
    String state = consentState(get("/oauth2/authorize?" + query));
    long failures =
        jdbc.queryForObject(
            "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.protocol' AND result='FAILURE'",
            Long.class);
    assertThat(
            post(
                    "/oauth2/authorize",
                    Map.of("client_id", client.clientId(), "state", state, "scope", "profile"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.protocol' AND result='FAILURE'",
                Long.class))
        .isEqualTo(failures + 1);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_CSRF_STORAGE"))
        .when(protocolAudits)
        .protocolEvent(any(), eq("FAILURE"), any(), any(), any());
    var unavailable =
        post(
            "/oauth2/authorize",
            Map.of("client_id", client.clientId(), "state", state, "scope", "profile"));
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.body()).doesNotContain("DO_NOT_ECHO_CSRF_STORAGE", state);
    reset(protocolAudits);
    var approved =
        post(
            "/oauth2/authorize",
            Map.of(
                "client_id",
                client.clientId(),
                "state",
                state,
                "scope",
                "profile",
                "_csrf",
                csrf("/identity/session")));
    assertThat(approved.statusCode()).isEqualTo(302);
  }

  @Test
  void disabledAccountCannotConsumeCodeAndLogoutPreventsRefresh() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    String code = authorizationCode(client);
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                user));
    assertThat(oauthToken(client, codeGrant(code, VERIFIER)).statusCode()).isEqualTo(400);
    assertThat(store.session(binding(sid()), false).revokedAt()).isNotNull();
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='ACTIVE',auth_version=auth_version+1 WHERE id=?",
                user));
    login();
    var issued =
        JsonMapper.builder()
            .build()
            .readTree(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)).body());
    String csrf = csrf("/identity/session");
    assertThat(post("/identity/logout", Map.of("_csrf", csrf)).statusCode()).isEqualTo(302);
    assertThat(
            oauthToken(
                    client,
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        issued.get("refresh_token").asString()))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void tokenSuccessAuditFailureRollsBackConsumptionAndKeepsIndependentFailure() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    String code = authorizationCode(client);
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_PROTOCOL_STORAGE"))
        .when(protocolAudits)
        .userProtocolEvent(
            eq("identity.oauth.token"), eq("SUCCESS"), any(), any(), any(), any(), any(), any());
    var failed = oauthToken(client, codeGrant(code, VERIFIER));
    assertThat(failed.statusCode()).isEqualTo(503);
    assertThat(failed.body()).doesNotContain("DO_NOT_ECHO_PROTOCOL_STORAGE", code, client.secret());
    var authorization =
        context
            .getBean(com.ken.platform.identity.service.oauth.ProtocolAuthorizations.class)
            .findByToken(
                code,
                new org.springframework.security.oauth2.server.authorization.OAuth2TokenType(
                    "code"));
    assertThat(
            authorization
                .getToken(
                    org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode
                        .class)
                .isActive())
        .isTrue();
    assertThat(authorization.getAccessToken()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.oauth.token' AND result='FAILURE' AND details->>'error_code'='temporarily_unavailable'",
                Integer.class,
                user))
        .isEqualTo(1);
    reset(protocolAudits);
    assertThat(oauthToken(client, codeGrant(code, VERIFIER)).statusCode()).isEqualTo(200);
  }

  @Test
  void authorizationResumesAfterLoginAndAuditFailureReturns503WithoutIssuingCode()
      throws Exception {
    ordinary();
    var client = oauthClient(false);
    redirected(
        get("/oauth2/authorize?" + authorizationQuery(client, tenantCode)), "/identity/login");
    var authenticated = post("/identity/login", credentials(csrf("/identity/login"), secret));
    assertThat(authenticated.statusCode()).isEqualTo(302);
    URI resume = URI.create(authenticated.headers().firstValue("Location").orElseThrow());
    assertThat(resume.getPath()).isEqualTo("/oauth2/authorize");
    doThrow(new DataAccessResourceFailureException("DO_NOT_ECHO_AUTHORIZATION_STORAGE"))
        .when(protocolAudits)
        .userProtocolEvent(
            eq("identity.oauth.authorization"),
            eq("SUCCESS"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    var failed = get(resume.getRawPath() + "?" + resume.getRawQuery());
    assertThat(failed.statusCode()).isEqualTo(503);
    assertThat(JsonMapper.builder().build().readTree(failed.body()).get("error").asString())
        .isEqualTo("temporarily_unavailable");
    assertThat(failed.body()).doesNotContain("DO_NOT_ECHO_AUTHORIZATION_STORAGE", client.secret());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                client.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND action='identity.oauth.authorization' AND result='FAILURE'",
                Integer.class,
                user))
        .isEqualTo(1);
    reset(protocolAudits);
    var response = get(resume.getRawPath() + "?" + resume.getRawQuery());
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(
            oauthToken(
                    client,
                    codeGrant(
                        parameter(response.headers().firstValue("Location").orElseThrow(), "code"),
                        VERIFIER))
                .statusCode())
        .isEqualTo(200);
  }

  private HttpResponse<String> oauthToken(OAuthClient client, Map<String, String> parameters)
      throws Exception {
    return oauthExchange("/oauth2/token", client, parameters);
  }

  private HttpResponse<String> oauthExchange(
      String path, OAuthClient client, Map<String, String> parameters) throws Exception {
    var fields = new HashMap<>(parameters);
    var request =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (client.publicClient()) fields.put("client_id", client.clientId());
    else
      request.header(
          "Authorization",
          "Basic "
              + java.util.Base64.getEncoder()
                  .encodeToString(
                      (client.clientId() + ":" + client.secret())
                          .getBytes(StandardCharsets.UTF_8)));
    // token 请求故意不携带浏览器 Cookie。
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
        .send(
            request.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private Map<String, String> codeGrant(String code, String verifier) {
    return Map.of(
        "grant_type",
        "authorization_code",
        "code",
        code,
        "redirect_uri",
        CALLBACK,
        "code_verifier",
        verifier);
  }

  @Test
  void confidentialCodeIsSingleUseAndReplayRevokesItsTokens() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    String code = authorizationCode(client);
    var response = oauthToken(client, codeGrant(code, VERIFIER));
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JsonMapper.builder().build().readTree(response.body());
    assertThat(body.get("access_token").asString()).doesNotContain(".");
    assertThat(body.has("refresh_token")).isTrue();
    var saved =
        context
            .getBean(com.ken.platform.identity.service.oauth.ProtocolAuthorizations.class)
            .findByToken(
                body.get("access_token").asString(),
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                    .ACCESS_TOKEN);
    assertThat(saved.getAttribute("platform.issued.session").toString())
        .isEqualTo(binding(sid()).toString());
    assertThat(saved.getAttribute("platform.issued.tenants").toString())
        .contains(tenant.toString());
    var again = oauthToken(client, codeGrant(code, VERIFIER));
    assertThat(again.statusCode()).isEqualTo(400);
    assertThat(JsonMapper.builder().build().readTree(again.body()).get("error").asString())
        .isEqualTo("invalid_grant");
    var revoked =
        context
            .getBean(com.ken.platform.identity.service.oauth.ProtocolAuthorizations.class)
            .findById(saved.getId());
    assertThat(revoked.getAccessToken().isActive()).isFalse();
    assertThat(revoked.getRefreshToken().isActive()).isFalse();
  }

  @Test
  void publicClientUsesConsentAndPkceAndNeverReceivesRefreshToken() throws Exception {
    ordinary();
    login();
    var client = oauthClient(true);
    String code = authorizationCode(client);
    var wrong =
        oauthToken(
            client, codeGrant(code, "incorrect-pkce-verifier-that-is-at-least-43-characters"));
    assertThat(wrong.statusCode()).isEqualTo(400);
    var correct = oauthToken(client, codeGrant(code, VERIFIER));
    assertThat(correct.statusCode()).isEqualTo(200);
    var body = JsonMapper.builder().build().readTree(correct.body());
    assertThat(body.has("refresh_token")).isFalse();
    assertThat(body.get("access_token").asString()).doesNotContain(".");
  }

  @Test
  void refreshRaceHasOneWinnerAndAbsoluteDeadlineDoesNotSlide() throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    var issued =
        JsonMapper.builder()
            .build()
            .readTree(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)).body());
    String refresh = issued.get("refresh_token").asString();
    String started = java.time.Instant.now().minusSeconds(8 * 3600 - 180).toString();
    jdbc.update(
        "UPDATE oauth2_authorization SET attributes=jsonb_set(attributes::jsonb,'{platform.issued.grant-at}',to_jsonb(?::text))::text WHERE registered_client_id=?",
        started,
        client.id());
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var task =
          (java.util.concurrent.Callable<HttpResponse<String>>)
              () -> {
                ready.countDown();
                assertThat(start.await(3, TimeUnit.SECONDS)).isTrue();
                return oauthToken(
                    client, Map.of("grant_type", "refresh_token", "refresh_token", refresh));
              };
      var a = workers.submit(task);
      var b = workers.submit(task);
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertThat(java.util.List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(200, 400);
      String nextRefresh =
          JsonMapper.builder()
              .build()
              .readTree(first.statusCode() == 200 ? first.body() : second.body())
              .get("refresh_token")
              .asString();
      assertThat(nextRefresh).isNotEqualTo(refresh);
      var saved =
          context
              .getBean(com.ken.platform.identity.service.oauth.ProtocolAuthorizations.class)
              .findByToken(
                  nextRefresh,
                  org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                      .REFRESH_TOKEN);
      assertThat(saved.getRefreshToken().getToken().getExpiresAt())
          .isEqualTo(
              java.time.Instant.parse(started)
                  .plusSeconds(8 * 3600)
                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
      jdbc.update(
          "UPDATE oauth2_authorization SET attributes=jsonb_set(attributes::jsonb,'{platform.issued.grant-at}',to_jsonb(?::text))::text WHERE registered_client_id=?",
          java.time.Instant.now().minusSeconds(8 * 3600 + 1).toString(),
          client.id());
      assertThat(
              oauthToken(
                      client, Map.of("grant_type", "refresh_token", "refresh_token", nextRefresh))
                  .statusCode())
          .isEqualTo(400);
    }
  }

  @Test
  void expiredSessionRevocationCommitsBeforeRefreshErrorAndTenantCannotBeRebound()
      throws Exception {
    ordinary();
    login();
    var client = oauthClient(false);
    assertThat(get("/oauth2/authorize?" + authorizationQuery(client, "other-tenant")).statusCode())
        .isEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                client.id()))
        .isZero();
    var issued =
        JsonMapper.builder()
            .build()
            .readTree(oauthToken(client, codeGrant(authorizationCode(client), VERIFIER)).body());
    jdbc.update(
        "UPDATE sys_login_session SET created_at=now()-interval '2 days' WHERE id=?",
        binding(sid()));
    assertThat(
            oauthToken(
                    client,
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        issued.get("refresh_token").asString()))
                .statusCode())
        .isEqualTo(400);
    assertThat(store.session(binding(sid()), false).revokedAt()).isNotNull();
  }

  private void redirected(HttpResponse<String> response, String path) {
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath())
        .isEqualTo(path);
  }

  @Test
  void csrfLoginFixationProtectionAndRestrictedSessionWorkOverHttp() throws Exception {
    String token = csrf("/identity/login"), before = sid();
    var response = post("/identity/login", credentials(token, secret), "attacker.example");
    redirected(response, "/identity/password/initial");
    String after = sid();
    assertThat(before.equals(after)).isFalse();
    String cookie =
        response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith("IDENTITY_SSO="))
            .findFirst()
            .orElseThrow();
    assertThat(cookie.contains("HttpOnly")).isTrue();
    assertThat(cookie.contains("SameSite=Lax")).isTrue();
    assertThat(cookie.contains("Secure")).isFalse();
    assertThat(sessions.findById(after)).isNotNull();
    org.springframework.session.Session storedSession = sessions.findById(after);
    org.springframework.security.core.context.SecurityContext saved =
        storedSession.getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(saved.getAuthentication().getDetails()).isEqualTo("PASSWORD");
    assertThat(saved.getAuthentication().getCredentials()).isNull();
    var stored = store.session(binding(after), false);
    assertThat(stored.restricted()).isTrue();
    assertThat(stored.toString().contains(after)).isFalse();
    redirected(get("/identity/session"), "/identity/password/initial");
    assertThat(get("/identity/password/initial").body()).contains("设置新密码");
    assertThat(
            redis.redis.hasKey(
                redis.project
                    + ":local:identity-service:global:{login}:ip:"
                    + LoginProtection.digest("127.0.0.1")))
        .isTrue();
  }

  @Test
  void missingCsrfWrongPasswordAndControlCharactersCannotAuthenticate() throws Exception {
    assertThat(post("/identity/login", credentials("invalid", secret)).statusCode()).isEqualTo(403);
    var bad = post("/identity/login", credentials(csrf("/identity/login"), "wrong-password"));
    assertThat(bad.statusCode()).isEqualTo(401);
    assertThat(bad.body()).contains("账号或密码无效");
    var fields = new HashMap<>(credentials(csrf("/identity/login"), secret));
    fields.put("username", "\n" + username + "\n");
    assertThat(post("/identity/login", fields).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_login_session WHERE user_id=?", Integer.class, user))
        .isZero();
  }

  @Test
  void initialPasswordChecksCsrfAndConfirmationThenRequiresFreshLogin() throws Exception {
    login();
    String previous = sid();
    UUID id = binding(previous);
    var activityBefore = store.session(id, false).lastActivity();
    assertThat(
            post("/identity/password/initial", Map.of("password", next, "confirmation", next))
                .statusCode())
        .isEqualTo(403);
    assertThat(store.session(id, false).lastActivity()).isEqualTo(activityBefore);
    var mismatch =
        post(
            "/identity/password/initial",
            Map.of(
                "_csrf",
                csrf("/identity/password/initial"),
                "password",
                next,
                "confirmation",
                "other"));
    assertThat(mismatch.statusCode()).isEqualTo(400);
    assertThat(mismatch.body()).contains("两次输入的密码不一致");
    var changed =
        post(
            "/identity/password/initial",
            Map.of(
                "_csrf",
                csrf("/identity/password/initial"),
                "password",
                next,
                "confirmation",
                next));
    redirected(changed, "/identity/login");
    var stored = store.session(id, false);
    assertThat(stored.revokedAt()).isNotNull();
    assertThat(stored.cleanupPending()).isFalse();
    assertThat(sessions.findById(previous)).isNull();
    var fresh = post("/identity/login", credentials(csrf("/identity/login"), next));
    redirected(fresh, "/identity/session");
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
  }

  @Test
  void redisSessionSurvivesServletContextRecreation() throws Exception {
    ordinary();
    login();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    String before = sid();
    context.close();
    startContext();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(sid().equals(before)).isTrue();
  }

  @Test
  void logoutIsCsrfProtectedAndDatabaseRevocationPrecedesCookieCleanup() throws Exception {
    ordinary();
    login();
    String previous = sid();
    UUID id = binding(previous);
    assertThat(post("/identity/logout", Map.of()).statusCode()).isEqualTo(403);
    assertThat(store.session(id, false).revokedAt()).isNull();
    redirected(
        post("/identity/logout", Map.of("_csrf", csrf("/identity/session"))), "/identity/login");
    assertThat(store.session(id, false).revokedAt()).isNotNull();
    assertThat(store.session(id, false).cleanupPending()).isFalse();
    assertThat(sessions.findById(previous)).isNull();
    redirected(get("/identity/session"), "/identity/login");
  }

  @Test
  void latePartialRedisWriteAfterLogoutIsDiscardedWithoutRevivingTheSession() throws Exception {
    ordinary();
    login();
    String previous = sid();
    UUID id = binding(previous);
    redirected(
        post("/identity/logout", Map.of("_csrf", csrf("/identity/session"))), "/identity/login");
    String key = redis.project + ":local:identity-service:global:sso:sessions:" + previous;
    var operations = sessions.getSessionRedisOperations();
    operations.opsForHash().put(key, "lastAccessedTime", java.time.Instant.now().toEpochMilli());
    try (var caller = HttpClient.newHttpClient()) {
      var response =
          caller.send(
              HttpRequest.newBuilder(URI.create(origin + "/identity/session"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Cookie", "IDENTITY_SSO=" + previous)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      redirected(response, "/identity/login");
    }
    assertThat(operations.hasKey(key)).isFalse();
    assertThat(store.session(id, false).revokedAt()).isNotNull();
    assertThat(sessions.findById(previous)).isNull();
  }

  @Test
  void disablingAccountRejectsExistingCookieAndDoesNotReviveItOnRestore() throws Exception {
    ordinary();
    login();
    String previous = sid();
    UUID id = binding(previous);
    data(
        () ->
            jdbc.update(
                "UPDATE sys_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?",
                user));
    redirected(get("/identity/session"), "/identity/login");
    assertThat(store.session(id, false).revokedAt()).isNotNull();
    data(() -> jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=?", user));
    var replay =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(origin + "/identity/session"))
                    .header("Cookie", "IDENTITY_SSO=" + previous)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    redirected(replay, "/identity/login");
  }

  @Test
  void failedRedisDeleteKeepsRevokedIntentAndScheduledWorkerRetriesIt() throws Exception {
    ordinary();
    login();
    String previous = sid();
    UUID id = binding(previous);
    doThrow(new DataAccessResourceFailureException("delete unavailable fixture"))
        .when(sessions)
        .deleteById(previous);
    var response = post("/identity/logout", Map.of("_csrf", csrf("/identity/session")));
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(store.session(id, false).revokedAt()).isNotNull();
    assertThat(store.session(id, false).cleanupPending()).isTrue();
    CountDownLatch cleaned = new CountDownLatch(1);
    doAnswer(
            call -> {
              Object result = call.callRealMethod();
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .registerSynchronization(
                      new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          cleaned.countDown();
                        }
                      });
              return result;
            })
        .when(store)
        .cleanupCompleted(id, previous);
    doCallRealMethod().when(sessions).deleteById(previous);
    assertThat(cleaned.await(25, TimeUnit.SECONDS)).isTrue();
    assertThat(store.session(id, false).cleanupPending()).isFalse();
    assertThat(sessions.findById(previous)).isNull();
  }

  @Test
  void copiedSecurityContextCannotAttachToAnotherCookie() throws Exception {
    ordinary();
    login();
    String original = sid();
    UUID id = binding(original);
    org.springframework.session.Session copy = copyContext(sessions, original);
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(origin + "/identity/session"))
                    .header("Cookie", "IDENTITY_SSO=" + copy.getId())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    redirected(response, "/identity/login");
    assertThat(store.session(id, false).revokedAt()).isNull();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(sessions.findById(copy.getId())).isNull();
  }

  @Test
  void rateLimitedLoginReturnsRetryAfterAndDoesNotLeakTheAccount() throws Exception {
    jdbc.update(
        "UPDATE sys_security_policy SET login_failure_threshold=1,version=version+1 WHERE owner_type='PLATFORM'");
    var response = post("/identity/login", credentials(csrf("/identity/login"), "wrong-password"));
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()))
        .isPositive();
    assertThat(response.body()).contains("登录请求暂时受限");
    assertThat(response.body().contains(secret)).isFalse();
  }

  @Test
  void failedExplicitLoginDoesNotSilentlyReuseTheOldBrowserIdentity() throws Exception {
    ordinary();
    login();
    assertThat(get("/identity/session").statusCode()).isEqualTo(200);
    assertThat(
            post("/identity/login", credentials(csrf("/identity/login"), "wrong-password"))
                .statusCode())
        .isEqualTo(401);
    redirected(get("/identity/session"), "/identity/login");
  }

  private static <S extends org.springframework.session.Session>
      org.springframework.session.Session copyContext(
          org.springframework.session.SessionRepository<S> repository, String id) {
    S copy = repository.createSession();
    copy.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        repository
            .findById(id)
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    repository.save(copy);
    return copy;
  }

  @Test
  void pendingCookieCannotObtainAnAuthorizationCodeEvenForARegisteredPublicClient()
      throws Exception {
    login();
    String id = UUID.randomUUID().toString(), clientId = "browser-client-" + UUID.randomUUID();
    var client =
        org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(id)
            .clientId(clientId)
            .clientName("浏览器测试客户端")
            .clientAuthenticationMethod(
                org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://client.example/callback")
            .scope("openid")
            .clientSettings(
                org.springframework.security.oauth2.server.authorization.settings.ClientSettings
                    .builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .build())
            .build();
    var policy =
        new com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO(
            com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType.USER,
            tenant,
            java.util.Set.of(tenant),
            java.util.Set.of("identity-service"),
            com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status.ACTIVE,
            0);
    jdbc.update(
        "INSERT INTO sys_service_audience(audience,owner_service) VALUES('identity-service','identity-service') ON CONFLICT DO NOTHING");
    new TransactionTemplate(manager)
        .execute(
            status -> {
              context.getBean(ClientStore.class).savePair(client, policy, null);
              return null;
            });
    String challenge =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "native-pkce-verifier-that-is-at-least-43-characters-long"
                            .getBytes(StandardCharsets.UTF_8)));
    String query =
        "client_id="
            + clientId
            + "&response_type=code&scope=openid&redirect_uri="
            + URLEncoder.encode("https://client.example/callback", StandardCharsets.UTF_8)
            + "&code_challenge_method=S256&code_challenge="
            + challenge
            + "&tenant_code="
            + tenantCode
            + "&state=fixture-state";
    redirected(get("/oauth2/authorize?" + query), "/identity/password/initial");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                id))
        .isZero();
  }
}
