package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.config.AuthorizationServerConfig;
import com.ken.platform.identity.config.IdentitySigningConfiguration;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.oauth.ClientPolicyRules.Definition;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 原生 PostgreSQL + 实际 Spring Security Servlet 过滤链；不是外部网络/BFF/完整应用启动验收。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class MachineOAuthFlowsTest {
  @TempDir static Path signingDirectory;
  private static Path signingFile;

  @BeforeAll
  static void signingFixture() throws Exception {
    var key =
        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
            .keyID("machine-test-key")
            .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
            .generate();
    signingFile = signingDirectory.resolve("signing.json");
    Files.writeString(
        signingFile,
        new com.nimbusds.jose.jwk.JWKSet(List.of(key), Map.of("active_kid", key.getKeyID()))
            .toString(false));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @EnableWebMvc
  @Import({AuthorizationServerConfig.class, IdentitySigningConfiguration.class})
  static class WebConfiguration {}

  private record Client(UUID id, Definition definition, String secret) {}

  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTransactionManager manager;
  private ClientStore store;
  private ClientManagementService service;
  private ProtocolAuthorizations authorizations;
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;
  private final UUID actor = UUID.randomUUID();
  private final String prefix = "flow-" + UUID.randomUUID();
  private final AtomicBoolean failSuccessAudit = new AtomicBoolean();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setup() throws Exception {
    var b =
        json.readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(b.get("test_only").asBoolean()).isTrue();
    assertThat(b.get("app_env").asString()).isEqualTo("local");
    assertThat(b.get("dbname").asString()).startsWith("t02t");
    var c = new HikariConfig();
    c.setJdbcUrl(
        "jdbc:postgresql://"
            + b.get("host").asString()
            + ":"
            + b.get("port").asInt()
            + "/"
            + b.get("dbname").asString());
    c.setUsername(b.get("username").asString());
    c.setPassword(Files.readString(Path.of(b.get("password_file").asString())).strip());
    c.setMaximumPoolSize(5);
    pool = new HikariDataSource(c);
    jdbc =
        new JdbcTemplate(pool) {
          @Override
          public int update(String sql, Object... args) {
            if (failSuccessAudit.get()
                && sql.startsWith("INSERT INTO sys_auth_audit(actor_type")
                && args.length > 2
                && "SUCCESS".equals(args[2])) {
              throw new DataAccessResourceFailureException(
                  "fixture injected SQL/secret must not escape");
            }
            return super.update(sql, args);
          }
        };
    manager = new JdbcTransactionManager(pool);
    store = new ClientStore(jdbc);
    var config = new Configuration();
    config.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    config.addMapper(UserManagementMapper.class);
    config.addMapper(AccountManagementMapper.class);
    config.addMapper(SecurityPolicyMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(pool);
    factory.setConfiguration(config);
    var template = new SqlSessionTemplate(factory.getObject());
    var accounts = template.getMapper(AccountManagementMapper.class);
    service =
        new ClientManagementService(
            manager,
            store,
            new ClientPolicyRules("local"),
            template.getMapper(UserManagementMapper.class),
            accounts,
            new PlatformManagementAuthorization(accounts),
            new ManagementAudits(new SecurityAuditRepository(jdbc), manager),
            new PasswordCredentials(template.getMapper(SecurityPolicyMapper.class)),
            Clock.systemUTC(),
            new com.ken.platform.identity.service.security.RecentAuthentication(
                new com.ken.platform.identity.service.security.SecurityPolicies(
                    template.getMapper(SecurityPolicyMapper.class)),
                Clock.systemUTC()));
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES (?,?,'测试管理员','{bcrypt}fixture',false)",
        actor,
        prefix);
    for (String name : List.of("identity-service", "document-service", "other-service")) {
      jdbc.update(
          "INSERT INTO sys_service_audience(audience,owner_service) VALUES (?,?) ON CONFLICT DO NOTHING",
          name,
          name);
    }
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test-bound-origin",
                Map.of(
                    "APP_ENV",
                    "local",
                    "PUBLIC_ORIGIN",
                    "http://localhost:18080",
                    "IDENTITY_SIGNING_JWK_SECRET_FILE",
                    signingFile.toString())));
    context.addBeanFactoryPostProcessor(
        beans -> {
          beans.registerSingleton("jdbcTemplate", jdbc);
          beans.registerSingleton("transactionManager", manager);
          beans.registerSingleton("clientStore", store);
          beans.registerSingleton("securityAudits", new SecurityAuditRepository(jdbc));
        });
    context.register(WebConfiguration.class);
    context.refresh();
    authorizations = context.getBean(ProtocolAuthorizations.class);
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    AuthorizationServerContextHolder.resetContext();
    if (context != null) context.close();
    if (pool != null) {
      try {
        jdbc.update("DELETE FROM sys_platform_user WHERE id=?", actor);
      } finally {
        pool.close();
      }
    }
    // 随机客户端、授权和不可删审计作为本轮证据保留，不改项目身份数据。
  }

  private <T> T authorized(Supplier<T> operation) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_platform:client:manage"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            Map.of(
                "principal_type",
                "platform",
                UserTokenService.AUTHENTICATED_AT,
                java.time.Instant.now()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    try (var ignored =
        CallContexts.bind(
            new CallContext(
                UUID.randomUUID().toString(), null, actor.toString(), null, null, null))) {
      return operation.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Client create(String suffix, Set<String> scopes) {
    var d =
        new Definition(
            prefix + suffix,
            "机器",
            PrincipalType.SERVICE,
            null,
            Set.of(),
            Set.of("identity-service"),
            scopes,
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    var created = authorized(() -> service.create(d));
    return new Client(created.client().id(), d, created.credential().revealOnce());
  }

  private void update(Client client, Set<String> scopes, Status status, long version) {
    var d = client.definition();
    var changed =
        new Definition(
            d.clientId(),
            d.name(),
            d.principalType(),
            d.ownerTenantId(),
            d.allowedTenantIds(),
            d.audiences(),
            scopes,
            d.redirectUris(),
            d.logoutUris(),
            false,
            false,
            status);
    authorized(() -> service.update(client.id(), version, changed));
  }

  private String basic(Client client) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                (client.definition().clientId() + ":" + client.secret())
                    .getBytes(StandardCharsets.UTF_8));
  }

  private MvcResult tokenResult(Client client, String scope) throws Exception {
    return mvc.perform(
            post("/oauth2/token")
                .header("Authorization", basic(client))
                .contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials")
                .param("scope", scope))
        .andReturn();
  }

  private String token(Client client, String scope) throws Exception {
    var result = tokenResult(client, scope);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.has("refresh_token")).isFalse();
    assertThat(body.has("code")).isFalse();
    assertThat(body.get("expires_in").asInt()).isBetween(590, 600);
    assertThat(body.get("access_token").asString()).doesNotContain(".");
    return body.get("access_token").asString();
  }

  private JsonNode introspect(Client caller, String token) throws Exception {
    var result =
        mvc.perform(
                post("/oauth2/introspect")
                    .header("Authorization", basic(caller))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", token))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  private void error(MvcResult result, int status, String code) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    var body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("error").asString()).isEqualTo(code);
    assertThat(body.size()).isEqualTo(1);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
  }

  @Test
  void officialBasicOpaqueIssuanceRestrictedIntrospectionAndRevocation() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var resource = create("-resource", Set.of("internal.introspect:identity-service"));
    String token = token(machine, "catalog.publish:document-service");
    assertThat(token.split("\\.")).hasSize(1);
    var active = introspect(resource, token);
    assertThat(active.get("active").asBoolean()).isTrue();
    assertThat(active.get("iss").asString()).isEqualTo("http://localhost:18080");
    assertThat(active.get("principal_type").asString()).isEqualTo("service");
    assertThat(active.get("sub").asString()).isEqualTo(machine.definition().clientId());
    assertThat(active.get("aud").get(0).asString()).isEqualTo("identity-service");
    assertThat(active.has("user_id")).isFalse();
    assertThat(active.has("tenant_id")).isFalse();
    var revoked =
        mvc.perform(
                post("/oauth2/revoke")
                    .header("Authorization", basic(machine))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", token))
            .andReturn();
    assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
    assertThat(introspect(resource, token).get("active").asBoolean()).isFalse();
    assertThat(authorizations.findByToken(token, null).getAccessToken().isInvalidated()).isTrue();
  }

  @Test
  void invalidSecretGrantScopeAndTenantExpansionAreRejectedWithoutEnvelope() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var bad =
        mvc.perform(
                post("/oauth2/token")
                    .header(
                        "Authorization",
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(
                                    (machine.definition().clientId() + ":wrong")
                                        .getBytes(StandardCharsets.UTF_8)))
                    .contentType("application/x-www-form-urlencoded")
                    .param("grant_type", "client_credentials"))
            .andReturn();
    error(bad, 401, "invalid_client");
    error(tokenResult(machine, "catalog.publish:other-service"), 400, "invalid_scope");
    var expanded =
        mvc.perform(
                post("/oauth2/token")
                    .header("Authorization", basic(machine))
                    .contentType("application/x-www-form-urlencoded")
                    .param("grant_type", "client_credentials")
                    .param("scope", "catalog.publish:document-service")
                    .param("tenant_id", UUID.randomUUID().toString()))
            .andReturn();
    error(expanded, 400, "invalid_request");
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.protocol' AND details->>'error_code'='unsupported_grant_type'",
            Integer.class);
    var password =
        mvc.perform(
                post("/oauth2/token")
                    .header("Authorization", basic(machine))
                    .contentType("application/x-www-form-urlencoded")
                    .param("grant_type", "password"))
            .andReturn();
    error(password, 400, "unsupported_grant_type");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE action='identity.oauth.protocol' AND details->>'error_code'='unsupported_grant_type'",
                Integer.class))
        .isEqualTo(before + 1);
  }

  @Test
  void introspectionCredentialIsFixedToAudienceAndPublisherCannotIntrospect() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var wrong = create("-wrong", Set.of("internal.introspect:other-service"));
    var right = create("-right", Set.of("internal.introspect:identity-service"));
    String value = token(machine, "catalog.publish:document-service");
    assertThat(introspect(wrong, value).get("active").asBoolean()).isFalse();
    var forbidden =
        mvc.perform(
                post("/oauth2/introspect")
                    .header("Authorization", basic(machine))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", value))
            .andReturn();
    error(forbidden, 400, "unauthorized_client");
    assertThat(introspect(right, "nonexistent-token").size()).isEqualTo(1);
    var refreshHint =
        mvc.perform(
                post("/oauth2/introspect")
                    .header("Authorization", basic(right))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", value)
                    .param("token_type_hint", "refresh_token"))
            .andReturn();
    assertThat(refreshHint.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            json.readTree(refreshHint.getResponse().getContentAsString()).get("active").asBoolean())
        .isTrue();
  }

  @Test
  void narrowingCurrentScopesNeverWidensPreviouslyIssuedTokenAndDisableBlocksBoth()
      throws Exception {
    var machine =
        create(
            "-publisher",
            Set.of("catalog.publish:document-service", "catalog.publish:other-service"));
    var resource = create("-resource", Set.of("internal.introspect:identity-service"));
    String value = token(machine, "catalog.publish:document-service");
    var original = authorizations.findByToken(value, null);
    update(machine, Set.of("catalog.publish:other-service"), Status.ACTIVE, 0);
    assertThat(introspect(resource, value).get("active").asBoolean()).isFalse();
    update(machine, machine.definition().scopes(), Status.ACTIVE, 1);
    var restored = introspect(resource, value);
    assertThat(restored.get("scope").asString()).isEqualTo("catalog.publish:document-service");
    assertThat(authorizations.findByToken(value, null).getAuthorizedScopes())
        .isEqualTo(original.getAuthorizedScopes());
    update(machine, machine.definition().scopes(), Status.DISABLED, 2);
    assertThat(introspect(resource, value).get("active").asBoolean()).isFalse();
    error(tokenResult(machine, "catalog.publish:document-service"), 401, "invalid_client");
  }

  @Test
  void successAuditFailureRollsBackTokenAndRecordsOnlyIndependentFailure() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    failSuccessAudit.set(true);
    error(tokenResult(machine, "catalog.publish:document-service"), 503, "temporarily_unavailable");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                machine.id().toString()))
        .isZero();
    var rows =
        jdbc.queryForList(
            "SELECT details::text FROM sys_auth_audit WHERE details->>'client_id'=? AND action='identity.oauth.token' AND result='FAILURE'",
            String.class,
            machine.definition().clientId());
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().contains("fixture injected")).isFalse();
    assertThat(rows.getFirst().contains(machine.secret())).isFalse();
  }

  @Test
  void newRepositoryInstanceReadsDatabaseAuthorizationAndRejectsExpiredToken() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var resource = create("-resource", Set.of("internal.introspect:identity-service"));
    String value = token(machine, "catalog.publish:document-service");
    var fresh = new ProtocolAuthorizations(jdbc, new ControlledRegisteredClients(store), store);
    assertThat(fresh.findByToken(value, null)).isNotNull();
    jdbc.update(
        "UPDATE oauth2_authorization SET access_token_issued_at=now()-interval '11 minutes',access_token_expires_at=now()-interval '1 second' WHERE registered_client_id=?",
        machine.id().toString());
    assertThat(introspect(resource, value).get("active").asBoolean()).isFalse();
    assertThatThrownBy(() -> fresh.save(authorizations.findByToken(value, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void issuingTransactionHoldsPolicyUntilOfficialTokenAndAuditCommit() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var client =
        new OAuth2ClientAuthenticationToken(
            store.byId(machine.id().toString()).client(),
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
            null);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var official =
        new OAuth2ClientCredentialsAuthenticationProvider(
            authorizations, new OAuth2AccessTokenGenerator());
    AuthenticationProvider slow =
        new AuthenticationProvider() {
          @Override
          public boolean supports(Class<?> type) {
            return official.supports(type);
          }

          @Override
          public Authentication authenticate(Authentication value) {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("fixture release timeout");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            return official.authenticate(value);
          }
        };
    var guarded =
        new ProtocolProvider(
            slow,
            ProtocolProvider.Operation.TOKEN,
            store,
            authorizations,
            new SecurityAuditRepository(jdbc),
            manager);
    var settings = context.getBean(AuthorizationServerSettings.class);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var issue =
          executor.submit(
              () -> {
                AuthorizationServerContextHolder.setContext(
                    new AuthorizationServerContext() {
                      @Override
                      public String getIssuer() {
                        return settings.getIssuer();
                      }

                      @Override
                      public AuthorizationServerSettings getAuthorizationServerSettings() {
                        return settings;
                      }
                    });
                try {
                  return guarded.authenticate(
                      new OAuth2ClientCredentialsAuthenticationToken(
                          client, machine.definition().scopes(), Map.of()));
                } finally {
                  AuthorizationServerContextHolder.resetContext();
                }
              });
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var disable =
          executor.submit(() -> update(machine, machine.definition().scopes(), Status.DISABLED, 0));
      try {
        assertThatThrownBy(() -> disable.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(issue.get(5, TimeUnit.SECONDS)).isNotNull();
      disable.get(5, TimeUnit.SECONDS);
    }
    assertThat(store.byId(machine.id().toString()).policy().status()).isEqualTo(Status.DISABLED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id=?",
                Integer.class,
                machine.id().toString()))
        .isEqualTo(1);
  }

  @Test
  void untrustedHostDoesNotChooseIssuerAndNonProtocolRoutesStayProtected() throws Exception {
    var metadata =
        mvc.perform(
                get("/.well-known/oauth-authorization-server")
                    .header("Host", "attacker.example")
                    .header("Forwarded", "host=attacker.example;proto=https"))
            .andReturn();
    assertThat(metadata.getResponse().getStatus()).isEqualTo(200);
    assertThat(json.readTree(metadata.getResponse().getContentAsString()).get("issuer").asString())
        .isEqualTo("http://localhost:18080");
    assertThat(mvc.perform(get("/private")).andReturn().getResponse().getStatus()).isIn(401, 403);
  }

  @Test
  void tokenBindingSurvivesOfficialJsonAndCannotBeRewrittenOrDeletedOnline() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    String value = token(machine, "catalog.publish:document-service");
    var saved = authorizations.findByToken(value, null);
    assertThat((String) saved.getAttribute(ProtocolAuthorizations.VERSION)).isEqualTo("0");
    assertThat((Object) saved.getAttribute(ProtocolAuthorizations.AUDIENCES))
        .isEqualTo(List.of("identity-service"));
    assertThat((Object) saved.getAttribute(ProtocolAuthorizations.TENANTS)).isEqualTo(List.of());
    var changed =
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(saved)
            .attribute(ProtocolAuthorizations.AUDIENCES, List.of("other-service"))
            .build();
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      authorizations.save(changed);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("不可改写历史授权绑定");
    assertThatThrownBy(() -> authorizations.remove(saved))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(
            (Object)
                authorizations
                    .findByToken(value, null)
                    .getAttribute(ProtocolAuthorizations.AUDIENCES))
        .isEqualTo(List.of("identity-service"));
  }

  @Test
  void revocationCannotAffectAnotherClientAndSuccessAuditFailureRollsItBack() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var other = create("-other", Set.of("catalog.publish:other-service"));
    var resource = create("-resource", Set.of("internal.introspect:identity-service"));
    String value = token(machine, "catalog.publish:document-service");
    var foreign =
        mvc.perform(
                post("/oauth2/revoke")
                    .header("Authorization", basic(other))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", value))
            .andReturn();
    error(foreign, 401, "invalid_client");
    assertThat(introspect(resource, value).get("active").asBoolean()).isTrue();
    failSuccessAudit.set(true);
    var failed =
        mvc.perform(
                post("/oauth2/revoke")
                    .header("Authorization", basic(machine))
                    .contentType("application/x-www-form-urlencoded")
                    .param("token", value))
            .andReturn();
    error(failed, 503, "temporarily_unavailable");
    assertThat(introspect(resource, value).get("active").asBoolean()).isTrue();
    assertThat(authorizations.findByToken(value, null).getAccessToken().isInvalidated()).isFalse();
  }

  @Test
  void disabledAudiencePreventsNewIssuance() throws Exception {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    jdbc.update(
        "UPDATE sys_service_audience SET status='DISABLED' WHERE audience='identity-service'");
    try {
      error(tokenResult(machine, "catalog.publish:document-service"), 400, "unauthorized_client");
    } finally {
      jdbc.update(
          "UPDATE sys_service_audience SET status='ACTIVE' WHERE audience='identity-service'");
    }
  }

  @Test
  void unexpectedProviderFailureHasMatchingSafeAuditAndProtocolCode() {
    var machine = create("-publisher", Set.of("catalog.publish:document-service"));
    var client =
        new OAuth2ClientAuthenticationToken(
            store.byId(machine.id().toString()).client(),
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
            null);
    AuthenticationProvider broken =
        new AuthenticationProvider() {
          @Override
          public boolean supports(Class<?> type) {
            return true;
          }

          @Override
          public Authentication authenticate(Authentication ignored) {
            throw new IllegalStateException("sensitive failure fixture");
          }
        };
    var guarded =
        new ProtocolProvider(
            broken,
            ProtocolProvider.Operation.TOKEN,
            store,
            authorizations,
            new SecurityAuditRepository(jdbc),
            manager);
    assertThatThrownBy(
            () ->
                guarded.authenticate(
                    new OAuth2ClientCredentialsAuthenticationToken(
                        client, machine.definition().scopes(), Map.of())))
        .isInstanceOfSatisfying(
            org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
            failure -> assertThat(failure.getError().getErrorCode()).isEqualTo("server_error"));
    var row =
        jdbc.queryForObject(
            "SELECT details::text FROM sys_auth_audit WHERE details->>'client_id'=? AND action='identity.oauth.token' AND result='FAILURE'",
            String.class,
            machine.definition().clientId());
    assertThat(json.readTree(row).get("error_code").asString()).isEqualTo("server_error");
    assertThat(row.contains("sensitive failure fixture")).isFalse();
  }

  @Test
  void tenantMachineUsesIssuedIntersectionAndCurrentTenantStatusAndExpiry() throws Exception {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    for (UUID id : List.of(a, b, c)) {
      jdbc.update(
          "INSERT INTO sys_tenant(id,code,name) VALUES (?,?,'机器范围测试')", id, "machine-" + id);
    }
    java.util.function.Function<Set<UUID>, Definition> definition =
        tenants ->
            new Definition(
                prefix + "-tenant-machine",
                "租户任务机器",
                PrincipalType.SERVICE,
                null,
                tenants,
                Set.of("document-service"),
                Set.of("document.read"),
                Set.of(),
                Set.of(),
                false,
                false,
                Status.ACTIVE);
    var created = authorized(() -> service.create(definition.apply(Set.of(a, b))));
    var machine =
        new Client(
            created.client().id(),
            created.client().definition(),
            created.credential().revealOnce());
    var resource = create("-document-introspector", Set.of("internal.introspect:document-service"));
    String value = token(machine, "document.read");
    var initial = introspect(resource, value);
    assertThat(json.convertValue(initial.get("allowed_tenant_ids"), String[].class))
        .containsExactlyInAnyOrder(a.toString(), b.toString());
    assertThat(initial.has("tenant_id")).isFalse();
    assertThat(initial.get("permissions").size()).isZero();
    assertThat(initial.get("data_scopes").size()).isZero();
    jdbc.update("UPDATE sys_tenant SET status='DISABLED' WHERE id=?", b);
    var current = introspect(resource, value);
    assertThat(json.convertValue(current.get("allowed_tenant_ids"), String[].class))
        .containsExactly(a.toString());
    assertThat(current.get("tenant_id").asString()).isEqualTo(a.toString());
    error(tokenResult(machine, "document.read"), 400, "unauthorized_client");
    authorized(() -> service.update(machine.id(), 0, definition.apply(Set.of(a))));
    jdbc.update("UPDATE sys_tenant SET status='ACTIVE' WHERE id=?", b);
    authorized(() -> service.update(machine.id(), 1, definition.apply(Set.of(a, b, c))));
    assertThat(
            json.convertValue(
                introspect(resource, value).get("allowed_tenant_ids"), String[].class))
        .containsExactlyInAnyOrder(a.toString(), b.toString());
    jdbc.update("UPDATE sys_tenant SET expires_at=now()-interval '1 second' WHERE id=?", a);
    assertThat(
            json.convertValue(
                introspect(resource, value).get("allowed_tenant_ids"), String[].class))
        .containsExactly(b.toString());
    jdbc.update("UPDATE sys_tenant SET expires_at=now()-interval '1 second' WHERE id=?", b);
    assertThat(introspect(resource, value).get("active").asBoolean()).isFalse();
  }
}
