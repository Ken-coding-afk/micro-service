package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import com.ken.platform.identity.service.oauth.ClientPolicyRules.Definition;
import com.ken.platform.identity.service.security.PasswordCredentials;
import com.ken.platform.identity.service.security.PasswordCredentials.OneTimeSecret;
import com.ken.platform.identity.service.security.RecentAuthentication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 协议记录、策略与审计的唯一管理用例；平台入口不依赖 HTTP 才能保持事务一致。 */
@Service
public final class ClientManagementService {
  public record View(UUID id, Definition definition, long version) {}

  public record Created(View client, OneTimeSecret credential, boolean replayed) {}

  public record Page(int page, int size, boolean hasMore, List<View> items) {
    public Page {
      items = List.copyOf(items);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CREATE = "identity.client.created";
  private final TransactionTemplate transactions;
  private final ClientStore store;
  private final ClientPolicyRules rules;
  private final UserManagementMapper users;
  private final AccountManagementMapper accounts;
  private final PlatformManagementAuthorization authorization;
  private final ManagementAudits audits;
  private final PasswordCredentials credentials;
  private final Clock clock;
  private final RecentAuthentication recent;

  public ClientManagementService(
      PlatformTransactionManager manager,
      ClientStore store,
      ClientPolicyRules rules,
      UserManagementMapper users,
      AccountManagementMapper accounts,
      PlatformManagementAuthorization authorization,
      ManagementAudits audits,
      PasswordCredentials credentials,
      Clock clock,
      RecentAuthentication recent) {
    transactions = new TransactionTemplate(manager);
    this.store = store;
    this.rules = rules;
    this.users = users;
    this.accounts = accounts;
    this.authorization = authorization;
    this.audits = audits;
    this.credentials = credentials;
    this.clock = clock;
    this.recent = recent;
  }

  public ClientStore.Audience registerService(String service, String audience) {
    return audits.attempt(
        ManagementAudits.Action.SERVICE_REGISTER,
        null,
        () -> {
          var actor = authorization.actorFor("platform:client:manage");
          if (service == null
              || !service.matches("[a-z][a-z0-9-]{0,127}")
              || service.equals("identity-service")
              || audience == null
              || !audience.matches("[A-Za-z0-9._:-]{1,160}")
              || audience.equals("identity-service"))
            throw new PlatformException("INVALID_ARGUMENT", "服务或 audience 不合法", 400);
          return transactions.execute(
              status -> {
                users.lockCatalog();
                authorization.requireActive(actor);
                recent.require(null);
                var bindings = store.findAudienceBindings(service, audience);
                if (!bindings.isEmpty()
                    && (bindings.size() != 1
                        || !bindings.getFirst().service().equals(service)
                        || !bindings.getFirst().audience().equals(audience)
                        || !bindings.getFirst().status().equals("ACTIVE")))
                  throw new PlatformException("SERVICE_AUDIENCE_CONFLICT", "服务绑定已存在且不一致，禁止覆盖", 409);
                if (bindings.isEmpty()) store.registerAudience(service, audience);
                accounts.audit(
                    actor,
                    "PLATFORM",
                    null,
                    "platform.service.registered",
                    service,
                    CallContexts.current().requestId(),
                    JSON.writeValueAsString(
                        Map.of("audience", audience, "created", bindings.isEmpty())));
                return new ClientStore.Audience(audience, service, "ACTIVE");
              });
        });
  }

  public Created create(Definition proposed) {
    return audits.attempt(
        ManagementAudits.Action.CLIENT_CREATE,
        null,
        () -> {
          UUID actor = authorization.actorFor("platform:client:manage");
          Definition value = rules.validate(proposed);
          String digest = digest(value);
          var issued = new AtomicReference<OneTimeSecret>();
          try {
            return transactions.execute(
                status -> {
                  users.lockCatalog();
                  authorization.requireActive(actor);
                  recent.require(null);
                  var prior =
                      accounts.prior(
                          "PLATFORM", actor, null, CREATE, CallContexts.current().requestId());
                  if (prior != null) {
                    if (!prior.digest().equals(digest)) {
                      throw new PlatformException("REQUEST_ID_CONFLICT", "相同请求标识不能用于不同客户端参数", 409);
                    }
                    String id = JSON.readTree(prior.result()).get("id").asString();
                    var previous = store.byId(id);
                    if (previous == null) {
                      throw new PlatformException("NOT_FOUND", "已创建的客户端不再存在", 404);
                    }
                    return new Created(view(previous), null, true);
                  }
                  if (store.clientIdExists(value.clientId())) {
                    throw new PlatformException("CLIENT_ID_CONFLICT", "客户端标识已存在", 409);
                  }
                  rules.validateReferences(value, store);
                  UUID id = UUID.randomUUID();
                  var material = value.publicClient() ? null : credentials.clientSecret();
                  if (material != null) {
                    issued.set(material.secret());
                  }
                  var client =
                      protocol(
                          value, id.toString(), material == null ? null : material.hash(), null);
                  var policy = policy(value, 0);
                  store.savePair(client, policy, null);
                  audit(
                      actor,
                      "identity.client.created",
                      id,
                      Map.of(
                          "client_id",
                          value.clientId(),
                          "credential_issued",
                          material != null,
                          "version",
                          0));
                  accounts.remember(
                      "PLATFORM",
                      actor,
                      null,
                      CREATE,
                      CallContexts.current().requestId(),
                      digest,
                      JSON.writeValueAsString(Map.of("id", id.toString())));
                  return new Created(
                      new View(id, value, 0), material == null ? null : material.secret(), false);
                });
          } catch (RuntimeException | Error failed) {
            if (issued.get() != null) {
              issued.get().close();
            }
            if (failed instanceof DuplicateKeyException) {
              throw new PlatformException("CLIENT_ID_CONFLICT", "客户端标识已存在", 409);
            }
            throw failed;
          }
        });
  }

  public Created rotate(UUID id, long expectedVersion) {
    return audits.attempt(
        ManagementAudits.Action.CLIENT_UPDATE,
        id,
        () -> {
          UUID actor = authorization.actorFor("platform:client:manage");
          var issued = new AtomicReference<OneTimeSecret>();
          try {
            return transactions.execute(
                status -> {
                  users.lockCatalog();
                  long version = store.lockPolicy(id.toString());
                  authorization.requireActive(actor);
                  recent.require(null);
                  if (version != expectedVersion)
                    throw new PlatformException(
                        "CLIENT_POLICY_VERSION_CONFLICT", "客户端已变化，请核对后轮换", 409);
                  var previous = store.byId(id.toString());
                  if (previous
                          .client()
                          .getClientAuthenticationMethods()
                          .contains(ClientAuthenticationMethod.NONE)
                      || previous.client().getClientId().startsWith("identity-admin-"))
                    throw ClientPolicyRules.invalid("公开客户端没有秘密；固定管理入口使用受控维护流程");
                  var material = credentials.clientSecret();
                  issued.set(material.secret());
                  var client =
                      RegisteredClient.from(previous.client())
                          .clientSecret(material.hash())
                          .build();
                  var definition = view(previous).definition();
                  var next = policy(definition, version + 1);
                  store.savePair(client, next, expectedVersion);
                  audit(
                      actor,
                      "identity.client.secret.rotated",
                      id,
                      Map.of("client_id", client.getClientId(), "version", next.version()));
                  return new Created(
                      new View(id, definition, next.version()), material.secret(), false);
                });
          } catch (RuntimeException | Error failure) {
            if (issued.get() != null) issued.get().close();
            throw failure;
          }
        });
  }

  public View update(UUID id, long expectedVersion, Definition proposed) {
    return audits.attempt(
        ManagementAudits.Action.CLIENT_UPDATE,
        id,
        () -> {
          UUID actor = authorization.actorFor("platform:client:manage");
          var value = rules.validate(proposed);
          return transactions.execute(
              status -> {
                users.lockCatalog();
                long version = store.lockPolicy(id.toString());
                authorization.requireActive(actor);
                recent.require(null);
                if (version != expectedVersion) {
                  throw new PlatformException(
                      "CLIENT_POLICY_VERSION_CONFLICT", "客户端配置已变化，请刷新后提交", 409);
                }
                var previous = store.byId(id.toString());
                var old = previous.policy();
                boolean publicClient =
                    previous
                        .client()
                        .getClientAuthenticationMethods()
                        .contains(ClientAuthenticationMethod.NONE);
                if (!previous.client().getClientId().equals(value.clientId())
                    || old.principalType() != value.principalType()
                    || !Objects.equals(old.ownerTenantId(), value.ownerTenantId())
                    || publicClient != value.publicClient()) {
                  throw ClientPolicyRules.invalid("客户端标识、主体类型、拥有租户和公开/机密类型不能重解释，请创建新客户端");
                }
                rules.validateReferences(value, store);
                var client =
                    protocol(
                        value,
                        id.toString(),
                        previous.client().getClientSecret(),
                        previous.client());
                var next = policy(value, version + 1);
                if (previous.client().equals(client)
                    && old.allowedTenantIds().equals(value.allowedTenantIds())
                    && old.audiences().equals(value.audiences())
                    && old.status() == value.status()) {
                  return view(previous);
                }
                store.savePair(client, next, expectedVersion);
                audit(
                    actor,
                    "identity.client.updated",
                    id,
                    Map.of(
                        "client_id",
                        value.clientId(),
                        "previous_status",
                        old.status().name(),
                        "status",
                        value.status().name(),
                        "version",
                        next.version()));
                return new View(id, value, next.version());
              });
        });
  }

  /** 仅维护用例可调用；与普通管理使用相同验证、协议构造与双表写入，不模拟登录主体。 */
  View initialize(String purpose, Definition proposed, String secret, String hash) {
    return initialize(purpose, proposed, secret, hash, false);
  }

  View initialize(
      String purpose, Definition proposed, String secret, String hash, boolean upgradeMonitoring) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("客户端 bootstrap 必须在维护事务内执行");
    var value = rules.validate(proposed);
    rules.validateReferences(value, store);
    var registered = store.bootstrapClient(purpose);
    var existing = store.byClientId(value.clientId());
    if (registered != null) {
      if (upgradeMonitoring
          && purpose.equals("PLATFORM_BFF")
          && existing != null
          && registered.id().equals(existing.client().getId())
          && registered.digest().equals(bootstrapDigest(purpose, view(existing).definition()))
          && credentials.matches(secret, existing.client().getClientSecret())
          && monitoringScopeUpgrade(view(existing).definition(), value)) {
        long version = existing.policy().version();
        store.savePair(
            protocol(
                value, registered.id(), existing.client().getClientSecret(), existing.client()),
            policy(value, version + 1),
            version);
        if (store.replaceBootstrapDigest(
                purpose, registered.id(), registered.digest(), bootstrapDigest(purpose, value))
            != 1) throw bootstrapConflict();
        accounts.audit(
            null,
            "SERVICE",
            null,
            "identity.bootstrap.monitoring.enabled",
            registered.id(),
            UUID.randomUUID().toString(),
            JSON.writeValueAsString(
                Map.of("client_id", value.clientId(), "added_scope", "platform.monitoring")));
        return new View(UUID.fromString(registered.id()), value, version + 1);
      }
      if (existing == null
          || !existing.client().getId().equals(registered.id())
          || !registered.digest().equals(bootstrapDigest(purpose, value))
          || !view(existing).definition().equals(value)
          || !credentials.matches(secret, existing.client().getClientSecret()))
        throw bootstrapConflict();
      return view(existing);
    }
    if (existing != null) throw bootstrapConflict();
    if (!credentials.matches(secret, hash)) throw bootstrapConflict();
    UUID id = UUID.randomUUID();
    store.savePair(protocol(value, id.toString(), hash, null), policy(value, 0), null);
    store.rememberBootstrap(purpose, id.toString(), bootstrapDigest(purpose, value));
    accounts.audit(
        null,
        "SERVICE",
        null,
        "identity.bootstrap.client.created",
        id.toString(),
        UUID.randomUUID().toString(),
        JSON.writeValueAsString(Map.of("purpose", purpose, "client_id", value.clientId())));
    return new View(id, value, 0);
  }

  /** 维护入口在目录锁事务内补齐服务客户端；既有策略归管理员，重跑不覆盖或扩权。 */
  View provision(Definition proposed, String secret) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("服务接入必须在维护事务内执行");
    var value = rules.validate(proposed);
    rules.validateReferences(value, store);
    var existing = store.byClientId(value.clientId());
    if (existing != null) {
      var current = view(existing);
      if (current.definition().principalType() != value.principalType()
          || current.definition().publicClient() != value.publicClient()
          || (!value.publicClient()
              && !credentials.matches(secret, existing.client().getClientSecret())))
        throw bootstrapConflict();
      // 当前策略是权威；重跑不覆盖管理员调整，不按旧摘要恢复或自动扩权。
      return current;
    }
    if (!value.publicClient() && (secret == null || !secret.matches("[A-Za-z0-9_-]{32,64}")))
      throw bootstrapConflict();
    UUID id = UUID.randomUUID();
    String hash = value.publicClient() ? null : credentials.encoder().encode(secret);
    store.savePair(protocol(value, id.toString(), hash, null), policy(value, 0), null);
    accounts.audit(
        null,
        "SERVICE",
        null,
        "identity.service.client.provisioned",
        id.toString(),
        UUID.randomUUID().toString(),
        JSON.writeValueAsString(Map.of("client_id", value.clientId())));
    return new View(id, value, 0);
  }

  /** 新租户事务已持目录锁；只追加已由 bootstrap 标记的固定租户管理客户端。 */
  public void enrollBootstrapTenant(UUID tenant) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("管理客户端租户关联必须位于创建租户的事务内");
    var registered = store.bootstrapClient("TENANT_BFF");
    if (registered == null) return;
    store.lockPolicy(registered.id());
    var old = store.byId(registered.id());
    if (old == null
        || old.policy().principalType() != PrincipalType.USER
        || old.policy().ownerTenantId() != null
        || !old.client().getClientId().equals("identity-admin-tenant")
        || old.policy().status() != ClientPolicyDO.Status.ACTIVE) throw bootstrapConflict();
    if (!registered.digest().equals(bootstrapDigest("TENANT_BFF", view(old).definition())))
      throw bootstrapConflict();
    if (old.policy().allowedTenantIds().contains(tenant)) return;
    var allowed = new java.util.HashSet<>(old.policy().allowedTenantIds());
    allowed.add(tenant);
    var before = view(old).definition();
    var value =
        rules.validate(
            new Definition(
                before.clientId(),
                before.name(),
                before.principalType(),
                null,
                Set.copyOf(allowed),
                before.audiences(),
                before.scopes(),
                before.redirectUris(),
                before.logoutUris(),
                before.publicClient(),
                before.firstParty(),
                before.status()));
    rules.validateReferences(value, store);
    store.savePair(old.client(), policy(value, old.policy().version() + 1), old.policy().version());
    accounts.audit(
        null,
        "SERVICE",
        tenant,
        "identity.bootstrap.tenant.enrolled",
        registered.id(),
        CallContexts.current().requestId(),
        JSON.writeValueAsString(Map.of("tenant_id", tenant.toString())));
  }

  static boolean monitoringScopeUpgrade(Definition before, Definition after) {
    if (before.scopes().contains("platform.monitoring")
        || !after.scopes().contains("platform.monitoring")) return false;
    var scopes = new java.util.HashSet<>(after.scopes());
    scopes.remove("platform.monitoring");
    return before.equals(
        new Definition(
            after.clientId(),
            after.name(),
            after.principalType(),
            after.ownerTenantId(),
            after.allowedTenantIds(),
            after.audiences(),
            Set.copyOf(scopes),
            after.redirectUris(),
            after.logoutUris(),
            after.publicClient(),
            after.firstParty(),
            after.status()));
  }

  private static PlatformException bootstrapConflict() {
    return new PlatformException("IDENTITY_BOOTSTRAP_CONFLICT", "已有初始化资源与登记不一致，停止且不覆盖", 409);
  }

  private static String bootstrapDigest(String purpose, Definition value) {
    // 创建租户只扩展固定管理客户端的租户集合，不改变受控初始化定义。
    if (!purpose.equals("TENANT_BFF")) return digest(value);
    return digest(
        new Definition(
            value.clientId(),
            value.name(),
            value.principalType(),
            null,
            Set.of(),
            value.audiences(),
            value.scopes(),
            value.redirectUris(),
            value.logoutUris(),
            value.publicClient(),
            value.firstParty(),
            value.status()));
  }

  public Page list(int page, int size, String query) {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        null,
        () -> {
          if (page < 0
              || page > 10000
              || size < 1
              || size > 100
              || query == null
              || query.length() > 100)
            throw new PlatformException("INVALID_ARGUMENT", "分页或搜索参数不合法", 400);
          UUID actor = authorization.actorFor("platform:client:read");
          return transactions.execute(
              status -> {
                users.lockCatalog();
                authorization.requireActive(actor);
                var ids = store.pageIds(query.strip(), page * size, size + 1);
                return new Page(
                    page,
                    size,
                    ids.size() > size,
                    ids.stream().limit(size).map(id -> view(store.byId(id))).toList());
              });
        });
  }

  public View get(UUID id) {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        id,
        () -> {
          if (id == null) throw new PlatformException("INVALID_ARGUMENT", "客户端标识不合法", 400);
          UUID actor = authorization.actorFor("platform:client:read");
          return transactions.execute(
              status -> {
                users.lockCatalog();
                authorization.requireActive(actor);
                var client = store.byId(id.toString());
                if (client == null) throw new PlatformException("NOT_FOUND", "客户端不存在或不可见", 404);
                return view(client);
              });
        });
  }

  private RegisteredClient protocol(
      Definition d, String id, String hash, RegisteredClient previous) {
    var builder =
        previous == null
            ? RegisteredClient.withId(id)
                .clientIdIssuedAt(clock.instant().truncatedTo(ChronoUnit.MICROS))
            : RegisteredClient.from(previous);
    builder
        .clientId(d.clientId())
        .clientName(d.name())
        .clientSecret(hash)
        .clientAuthenticationMethods(
            methods -> {
              methods.clear();
              methods.add(
                  d.publicClient()
                      ? ClientAuthenticationMethod.NONE
                      : ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            })
        .authorizationGrantTypes(
            grants -> {
              grants.clear();
              grants.add(
                  d.principalType() == PrincipalType.SERVICE
                      ? AuthorizationGrantType.CLIENT_CREDENTIALS
                      : AuthorizationGrantType.AUTHORIZATION_CODE);
              if (d.principalType() != PrincipalType.SERVICE && !d.publicClient()) {
                grants.add(AuthorizationGrantType.REFRESH_TOKEN);
              }
            })
        .redirectUris(
            values -> {
              values.clear();
              values.addAll(d.redirectUris());
            })
        .postLogoutRedirectUris(
            values -> {
              values.clear();
              values.addAll(d.logoutUris());
            })
        .scopes(
            values -> {
              values.clear();
              values.addAll(d.scopes());
            })
        .clientSettings(
            ClientSettings.builder()
                .requireProofKey(d.principalType() != PrincipalType.SERVICE)
                .requireAuthorizationConsent(!d.firstParty())
                .build())
        .tokenSettings(
            TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofMinutes(10))
                .refreshTokenTimeToLive(Duration.ofHours(8))
                .reuseRefreshTokens(false)
                .build());
    return builder.build();
  }

  private static ClientPolicyDO policy(Definition d, long version) {
    return new ClientPolicyDO(
        d.principalType(),
        d.ownerTenantId(),
        d.allowedTenantIds(),
        d.audiences(),
        d.status(),
        version);
  }

  private static View view(ClientStore.Snapshot snapshot) {
    var c = snapshot.client();
    var p = snapshot.policy();
    return new View(
        UUID.fromString(c.getId()),
        new Definition(
            c.getClientId(),
            c.getClientName(),
            p.principalType(),
            p.ownerTenantId(),
            p.allowedTenantIds(),
            p.audiences(),
            c.getScopes(),
            c.getRedirectUris(),
            c.getPostLogoutRedirectUris(),
            c.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE),
            !c.getClientSettings().isRequireAuthorizationConsent(),
            p.status()),
        p.version());
  }

  private void audit(UUID actor, String action, UUID id, Map<String, Object> details) {
    accounts.audit(
        actor,
        "PLATFORM",
        null,
        action,
        id.toString(),
        CallContexts.current().requestId(),
        JSON.writeValueAsString(details));
  }

  private static String digest(Definition d) {
    Map<String, Object> values = new TreeMap<>();
    values.put("client_id", d.clientId());
    values.put("name", d.name());
    values.put("principal_type", d.principalType().name());
    values.put("owner_tenant_id", d.ownerTenantId() == null ? "" : d.ownerTenantId().toString());
    values.put(
        "allowed_tenant_ids", d.allowedTenantIds().stream().map(UUID::toString).sorted().toList());
    values.put("audiences", d.audiences().stream().sorted().toList());
    values.put("scopes", d.scopes().stream().sorted().toList());
    values.put("redirect_uris", d.redirectUris().stream().sorted().toList());
    values.put("logout_uris", d.logoutUris().stream().sorted().toList());
    values.put("public_client", d.publicClient());
    values.put("first_party", d.firstParty());
    values.put("status", d.status().name());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }
}
