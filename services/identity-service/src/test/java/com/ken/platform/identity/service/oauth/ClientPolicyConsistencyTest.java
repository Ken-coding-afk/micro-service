package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.data.UuidTypeHandler;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
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
import com.ken.platform.identity.service.security.RecentAuthentication;
import com.ken.platform.identity.service.security.SecurityPolicies;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import tools.jackson.databind.json.JsonMapper;

/** 官方 JDBC + PostgreSQL 实测；合成平台主体不算真实 OAuth 协议登录。 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
class ClientPolicyConsistencyTest {
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private JdbcTransactionManager manager;
  private ClientStore store;
  private UserManagementMapper users;
  private AccountManagementMapper accounts;
  private PasswordCredentials credentials;
  private SecurityPolicies policies;
  private ClientManagementService service;
  private UUID tenantA;
  private UUID tenantB;
  private final UUID actor = UUID.randomUUID();
  private final String prefix = "client-" + UUID.randomUUID();
  private final List<PasswordCredentials.OneTimeSecret> secrets = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    var b =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    assertThat(b.get("test_only").asBoolean()).isTrue();
    assertThat(b.get("app_env").asString()).isEqualTo("local");
    assertThat(b.get("dbname").asString()).startsWith("t02t");
    tenantA = UUID.fromString(b.get("tenant_a").asString());
    tenantB = UUID.fromString(b.get("tenant_b").asString());
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
    c.setMaximumPoolSize(4);
    pool = new HikariDataSource(c);
    jdbc = new JdbcTemplate(pool);
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
    users = template.getMapper(UserManagementMapper.class);
    accounts = template.getMapper(AccountManagementMapper.class);
    credentials = new PasswordCredentials(template.getMapper(SecurityPolicyMapper.class));
    policies = new SecurityPolicies(template.getMapper(SecurityPolicyMapper.class));
    service = service(store, accounts);
    jdbc.update(
        "INSERT INTO sys_platform_user(id,normalized_username,display_name,password_hash,must_change_password) VALUES (?,?,'客户端管理员','{bcrypt}fixture',false)",
        actor,
        prefix);
    for (String name : List.of("identity-service", "document-service", "other-service")) {
      jdbc.update(
          "INSERT INTO sys_service_audience(audience,owner_service) VALUES (?,?) ON CONFLICT DO NOTHING",
          name,
          name);
    }
    jdbc.update(
        "INSERT INTO sys_permission(source,code,name,owner_service,principal_type,group_code,group_name,resource_code,resource_name,action_code,supported_data_scopes,oauth_scope) VALUES ('PUBLISHED','client-alt:read','读取','other-service','TENANT','other','其他','other','其他','read',ARRAY['SELF','TENANT'],'document.read') ON CONFLICT DO NOTHING");
  }

  private ClientManagementService service(ClientStore data, AccountManagementMapper mapper) {
    return new ClientManagementService(
        manager,
        data,
        new ClientPolicyRules("local"),
        users,
        mapper,
        new PlatformManagementAuthorization(mapper),
        new ManagementAudits(new SecurityAuditRepository(jdbc), manager),
        credentials,
        Clock.systemUTC(),
        new RecentAuthentication(policies, Clock.systemUTC()));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    secrets.forEach(PasswordCredentials.OneTimeSecret::close);
    if (pool != null) {
      try {
        jdbc.update("DELETE FROM sys_platform_user WHERE id=?", actor);
      } finally {
        pool.close();
      }
    }
    // 服务 audience、客户端、不可删审计/幂等均保留随机 ID 证据，不使用运行角色删协议历史。
  }

  private <T> T authorized(String request, Supplier<T> action) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("PERMISSION_platform:client:manage"));
    var p =
        new DefaultOAuth2AuthenticatedPrincipal(
            actor.toString(),
            Map.of(
                "principal_type",
                "platform",
                UserTokenService.AUTHENTICATED_AT,
                java.time.Instant.now()),
            authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, authorities));
    try (var ignored =
        CallContexts.bind(new CallContext(request, null, actor.toString(), null, null, null))) {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Definition user(String suffix, boolean publicClient) {
    return new Definition(
        prefix + suffix,
        "客户端",
        PrincipalType.USER,
        tenantA,
        Set.of(tenantA),
        Set.of("document-service"),
        Set.of("openid", "document.read"),
        Set.of("https://app.example/callback"),
        Set.of("https://app.example/logout"),
        publicClient,
        false,
        Status.ACTIVE);
  }

  private ClientManagementService.Created create(Definition d) {
    var result = authorized("create-" + UUID.randomUUID(), () -> service.create(d));
    if (result.credential() != null) {
      secrets.add(result.credential());
    }
    return result;
  }

  private Definition edit(
      Definition d, String name, Set<String> audiences, Set<String> scopes, Status status) {
    return new Definition(
        d.clientId(),
        name,
        d.principalType(),
        d.ownerTenantId(),
        d.allowedTenantIds(),
        audiences,
        scopes,
        d.redirectUris(),
        d.logoutUris(),
        d.publicClient(),
        d.firstParty(),
        status);
  }

  private ClientManagementService.View update(UUID id, long version, Definition d) {
    return authorized("update-" + UUID.randomUUID(), () -> service.update(id, version, d));
  }

  private static void failure(
      String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .isInstanceOfSatisfying(
            PlatformException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.status()).isEqualTo(status);
            });
  }

  @Test
  void secretRotationIsAtomicVersionedAndRejectsPublicClient() {
    var created = create(user("-rotate", false));
    String oldSecret = created.credential().revealOnce();
    var rotated =
        authorized("rotate-" + UUID.randomUUID(), () -> service.rotate(created.client().id(), 0));
    secrets.add(rotated.credential());
    String newSecret = rotated.credential().revealOnce();
    var saved = store.byId(created.client().id().toString());
    assertThat(saved.policy().version()).isEqualTo(1);
    assertThat(credentials.matches(oldSecret, saved.client().getClientSecret())).isFalse();
    assertThat(credentials.matches(newSecret, saved.client().getClientSecret())).isTrue();
    failure(
        "CLIENT_POLICY_VERSION_CONFLICT",
        409,
        () ->
            authorized(
                "stale-" + UUID.randomUUID(), () -> service.rotate(created.client().id(), 0)));
    assertThat(store.byId(created.client().id().toString()).client().getClientSecret())
        .isEqualTo(saved.client().getClientSecret());
    var publicClient = create(user("-public-rotate", true));
    assertThatThrownBy(
            () ->
                authorized(
                    "public-" + UUID.randomUUID(),
                    () -> service.rotate(publicClient.client().id(), 0)))
        .isInstanceOf(PlatformException.class);
    assertThat(store.byId(publicClient.client().id().toString()).policy().version()).isZero();
  }

  @Test
  void confidentialClientPersistsOfficialProtocolAndPolicyAndOnlyReturnsSecretOnce() {
    var d = user("-confidential", false);
    var created = create(d);
    String clear = created.credential().revealOnce();
    var saved = store.byId(created.client().id().toString());
    assertThat(credentials.matches(clear, saved.client().getClientSecret())).isTrue();
    assertThat(saved.client().getClientAuthenticationMethods())
        .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    assertThat(saved.client().getAuthorizationGrantTypes())
        .containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    assertThat(saved.client().getClientSettings().isRequireProofKey()).isTrue();
    assertThat(saved.client().getTokenSettings().getAccessTokenFormat())
        .isEqualTo(OAuth2TokenFormat.REFERENCE);
    assertThat(saved.client().getTokenSettings().getAccessTokenTimeToLive())
        .isEqualTo(Duration.ofMinutes(10));
    assertThat(saved.client().getTokenSettings().getRefreshTokenTimeToLive())
        .isEqualTo(Duration.ofHours(8));
    assertThat(saved.client().getTokenSettings().isReuseRefreshTokens()).isFalse();
    assertThat(saved.policy().allowedTenantIds()).containsExactly(tenantA);
    assertThat(saved.policy().version()).isZero();
    assertThat(saved.toString()).doesNotContain(clear, saved.client().getClientSecret());
    assertThat(
            jdbc.queryForObject(
                "SELECT details::text FROM sys_auth_audit WHERE target_id=? AND result='SUCCESS'",
                String.class,
                created.client().id().toString()))
        .doesNotContain(clear, "bcrypt");
  }

  @Test
  void publicClientHasNoSecretOrRefreshGrant() {
    var created = create(user("-public", true));
    var saved = store.byId(created.client().id().toString());
    assertThat(created.credential()).isNull();
    assertThat(saved.client().getClientSecret()).isNull();
    assertThat(saved.client().getClientAuthenticationMethods())
        .containsExactly(ClientAuthenticationMethod.NONE);
    assertThat(saved.client().getAuthorizationGrantTypes())
        .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
    assertThat(saved.client().getClientSettings().isRequireAuthorizationConsent()).isTrue();
  }

  @Test
  void repeatedRequestNeverReplaysSecretAndChangedRequestConflicts() {
    var d = user("-dedup", false);
    var first = authorized(prefix, () -> service.create(d));
    secrets.add(first.credential());
    var again = authorized(prefix, () -> service.create(d));
    assertThat(again.client().id()).isEqualTo(first.client().id());
    assertThat(again.replayed()).isTrue();
    assertThat(again.credential()).isNull();
    failure(
        "REQUEST_ID_CONFLICT",
        409,
        () ->
            authorized(
                prefix,
                () -> service.create(edit(d, "其他名称", d.audiences(), d.scopes(), Status.ACTIVE))));
    failure("CLIENT_ID_CONFLICT", 409, () -> create(d));
  }

  @Test
  void updateVersionAndCurrentSnapshotChangeTogetherAndNoopStaysStable() {
    var created = create(user("-update", false));
    var d = created.client().definition();
    assertThat(update(created.client().id(), 0, d).version()).isZero();
    var narrowed = edit(d, "收紧", d.audiences(), Set.of("openid"), Status.DISABLED);
    assertThat(update(created.client().id(), 0, narrowed).version()).isEqualTo(1);
    var saved = store.byId(created.client().id().toString());
    assertThat(saved.client().getScopes()).containsExactly("openid");
    assertThat(saved.policy().status()).isEqualTo(Status.DISABLED);
    assertThat(saved.policy().version()).isEqualTo(1);
    failure("CLIENT_POLICY_VERSION_CONFLICT", 409, () -> update(created.client().id(), 0, d));
    assertThat(
            update(
                    created.client().id(),
                    1,
                    edit(narrowed, "恢复", d.audiences(), d.scopes(), Status.ACTIVE))
                .version())
        .isEqualTo(2);
  }

  @Test
  void partialPairWriteAndSuccessAuditFailureBothRollBackActualTables() {
    var failing = spy(store);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("fixture-pair-failure");
            })
        .when(failing)
        .savePair(any(), any(), isNull());
    service = service(failing, accounts);
    var d = user("-pair-failure", false);
    assertThatThrownBy(() -> create(d)).isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_registered_client WHERE client_id=?",
                Integer.class,
                d.clientId()))
        .isZero();
    var auditFailure = spy(accounts);
    doThrow(new IllegalStateException("fixture-audit-failure"))
        .when(auditFailure)
        .audit(any(), anyString(), any(), anyString(), anyString(), anyString(), anyString());
    service = service(store, auditFailure);
    var second = user("-audit-failure", false);
    assertThatThrownBy(() -> create(second)).isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_registered_client WHERE client_id=?",
                Integer.class,
                second.clientId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_auth_audit WHERE actor_id=? AND result='FAILURE'",
                Integer.class,
                actor))
        .isEqualTo(2);
  }

  @Test
  void orphanProtocolRecordFailsClosedAndProtocolRepositoryCannotWrite() {
    var raw =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(prefix + "-orphan")
            .clientName("孤立记录")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://a.example/cb")
            .scope("openid")
            .build();
    new JdbcRegisteredClientRepository(jdbc).save(raw);
    failure("CLIENT_CONFIGURATION_ERROR", 503, () -> store.byClientId(raw.getClientId()));
    var controlled = new ControlledRegisteredClients(store);
    assertThatThrownBy(() -> controlled.save(raw))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(controlled.findByClientId(prefix + "-missing")).isNull();
  }

  @Test
  void actualPolicyConstraintFailureRollsBackEarlierOfficialRowWrite() {
    var invalid =
        new ClientStore(jdbc) {
          @Override
          public void savePair(RegisteredClient client, ClientPolicyDO p, Long expected) {
            super.savePair(
                client,
                new ClientPolicyDO(
                    p.principalType(),
                    p.ownerTenantId(),
                    p.allowedTenantIds(),
                    p.audiences(),
                    p.status(),
                    -1),
                expected);
          }
        };
    service = service(invalid, accounts);
    var d = user("-constraint", false);
    assertThatThrownBy(() -> create(d))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_registered_client WHERE client_id=?",
                Integer.class,
                d.clientId()))
        .isZero();
  }

  @Test
  void ownerAndScopeAudienceReferencesCannotBeExpandedOrGuessed() {
    var d = user("-bad-refs", false);
    failure(
        "CLIENT_REFERENCE_INVALID",
        400,
        () -> create(edit(d, d.name(), Set.of("missing-audience"), d.scopes(), Status.ACTIVE)));
    failure(
        "CLIENT_SCOPE_NOT_REGISTERED",
        400,
        () -> create(edit(d, d.name(), d.audiences(), Set.of("arbitrary.scope"), Status.ACTIVE)));
    var created = create(d);
    var changedOwner =
        new Definition(
            d.clientId(),
            d.name(),
            d.principalType(),
            tenantB,
            Set.of(tenantB),
            d.audiences(),
            d.scopes(),
            d.redirectUris(),
            d.logoutUris(),
            false,
            false,
            Status.ACTIVE);
    failure("CLIENT_DEFINITION_INVALID", 400, () -> update(created.client().id(), 0, changedOwner));
  }

  @Test
  void projectPublisherAndTenantBusinessMachineHaveSeparateBounds() {
    var publisher =
        new Definition(
            prefix + "-publisher",
            "发布",
            PrincipalType.SERVICE,
            null,
            Set.of(),
            Set.of("identity-service"),
            Set.of("catalog.publish:new-service"),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    var created = create(publisher);
    assertThat(store.byId(created.client().id().toString()).client().getAuthorizationGrantTypes())
        .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
    var noTenantBusiness =
        new Definition(
            prefix + "-unbounded",
            "越界",
            PrincipalType.SERVICE,
            null,
            Set.of(),
            Set.of("document-service"),
            Set.of("document.read"),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    failure("CLIENT_SCOPE_NOT_REGISTERED", 400, () -> create(noTenantBusiness));
    var machine =
        new Definition(
            prefix + "-machine",
            "机器",
            PrincipalType.SERVICE,
            tenantA,
            Set.of(tenantA),
            noTenantBusiness.audiences(),
            noTenantBusiness.scopes(),
            Set.of(),
            Set.of(),
            false,
            false,
            Status.ACTIVE);
    create(machine);
  }

  @Test
  void officialAuthorizationAndConsentRoundTripWithPostgresTextAndTimestamptz() {
    var created = create(user("-protocol", false));
    var client = store.byId(created.client().id().toString()).client();
    var repository = new ControlledRegisteredClients(store);
    var authorizations = new JdbcOAuth2AuthorizationService(jdbc, repository);
    var consents = new JdbcOAuth2AuthorizationConsentService(jdbc, repository);
    var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String token = "test-token-" + UUID.randomUUID();
    var authorization =
        OAuth2Authorization.withRegisteredClient(client)
            .id(UUID.randomUUID().toString())
            .principalName(actor.toString())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("document.read"))
            .token(
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    token,
                    now,
                    now.plusSeconds(600),
                    Set.of("document.read")))
            .build();
    authorizations.save(authorization);
    assertThat(
            authorizations
                .findByToken(token, OAuth2TokenType.ACCESS_TOKEN)
                .getAccessToken()
                .getToken()
                .getIssuedAt())
        .isEqualTo(now);
    var consent =
        OAuth2AuthorizationConsent.withId(client.getId(), actor.toString())
            .scope("document.read")
            .build();
    consents.save(consent);
    assertThat(consents.findById(client.getId(), actor.toString()).getScopes())
        .containsExactly("document.read");
    consents.remove(consent);
    authorizations.remove(authorization);
  }

  @Test
  void concurrentUpdatesReadOneProtocolPolicyEpochAndRejectSameVersionWriters() throws Exception {
    var original = user("-epochs", false);
    var a =
        new Definition(
            original.clientId(),
            "epoch-a",
            PrincipalType.USER,
            null,
            Set.of(tenantA),
            Set.of("document-service"),
            Set.of("document.read"),
            original.redirectUris(),
            original.logoutUris(),
            false,
            false,
            Status.ACTIVE);
    var b =
        new Definition(
            original.clientId(),
            "epoch-b",
            PrincipalType.USER,
            null,
            Set.of(tenantB),
            Set.of("other-service"),
            Set.of("document.read"),
            original.redirectUris(),
            original.logoutUris(),
            false,
            false,
            Status.ACTIVE);
    var created = create(a);
    var done = new AtomicBoolean();
    var reads = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          executor.submit(
              () -> {
                try {
                  for (int n = 0; n < 20; n++) {
                    update(created.client().id(), n, n % 2 == 0 ? b : a);
                  }
                } finally {
                  done.set(true);
                }
              });
      var reader =
          executor.submit(
              () -> {
                while (!done.get() || reads.get() < 100) {
                  var snapshot = store.byId(created.client().id().toString());
                  boolean first = snapshot.client().getClientName().equals("epoch-a");
                  assertThat(snapshot.policy().allowedTenantIds())
                      .isEqualTo(first ? Set.of(tenantA) : Set.of(tenantB));
                  assertThat(snapshot.policy().audiences())
                      .isEqualTo(first ? Set.of("document-service") : Set.of("other-service"));
                  reads.incrementAndGet();
                }
              });
      writer.get(20, TimeUnit.SECONDS);
      reader.get(20, TimeUnit.SECONDS);
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var jobs =
          List.of(a, b).stream()
              .map(
                  d ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await(5, TimeUnit.SECONDS);
                            try {
                              update(
                                  created.client().id(),
                                  20,
                                  edit(
                                      d, d.name() + "-new", d.audiences(), d.scopes(), d.status()));
                              return "OK";
                            } catch (PlatformException e) {
                              return e.code();
                            }
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  jobs.getFirst().get(10, TimeUnit.SECONDS),
                  jobs.getLast().get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "CLIENT_POLICY_VERSION_CONFLICT");
    }
  }

  @Test
  void serviceAudienceRegistrationIsIdempotentAndCannotRebind() {
    String name = prefix + "-resource";
    var first = authorized("register-1", () -> service.registerService(name, name));
    var second = authorized("register-2", () -> service.registerService(name, name));
    assertThat(second).isEqualTo(first);
    assertThat(store.audienceExists(name)).isTrue();
    failure(
        "SERVICE_AUDIENCE_CONFLICT",
        409,
        () -> authorized("register-3", () -> service.registerService(name, name + "-other")));
    failure(
        "SERVICE_AUDIENCE_CONFLICT",
        409,
        () -> authorized("register-4", () -> service.registerService(name + "-other", name)));
    assertThat(store.findAudienceBindings(name, name)).hasSize(1);
  }

  @Test
  void registrationRejectsAnonymousAndReservedIdentity() {
    failure("INVALID_CREDENTIALS", 401, () -> service.registerService(prefix, prefix));
    failure(
        "INVALID_ARGUMENT",
        400,
        () ->
            authorized(
                "register-reserved", () -> service.registerService("identity-service", prefix)));
  }
}
