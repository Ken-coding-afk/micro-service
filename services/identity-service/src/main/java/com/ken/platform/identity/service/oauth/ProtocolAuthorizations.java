package com.ken.platform.identity.service.oauth;

import com.ken.platform.identity.dal.mapper.oauth.AuthorizationStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 官方 JDBC 存储加受控签发上限；不把当前客户端配置覆盖进历史授权。 */
public final class ProtocolAuthorizations implements OAuth2AuthorizationService {
  static final String AUDIENCES = "platform.issued.audiences";
  static final String TENANTS = "platform.issued.tenants";
  static final String TYPE = "platform.issued.type";
  static final String VERSION = "platform.issued.client-version";
  static final String SESSION = "platform.issued.session";
  static final String GRANT_AT = "platform.issued.grant-at";
  static final String REVOKED = "platform.revoked-at";
  private static final List<String> BINDINGS =
      List.of(AUDIENCES, TENANTS, TYPE, VERSION, SESSION, GRANT_AT);
  private final AuthorizationStore official;
  private final ClientStore clients;
  private final ThreadLocal<ClientStore.Snapshot> issuance = new ThreadLocal<>();
  private final ThreadLocal<com.ken.platform.identity.service.login.PasswordLoginService.Identity>
      userIssuance = new ThreadLocal<>();

  public ProtocolAuthorizations(
      JdbcTemplate jdbc, RegisteredClientRepository clients, ClientStore store) {
    this.official = new AuthorizationStore(jdbc, clients);
    this.clients = store;
  }

  <T> T issuing(ClientStore.Snapshot snapshot, Supplier<T> action) {
    requireTransaction();
    if (issuance.get() != null) throw new IllegalStateException("不允许嵌套协议签发");
    issuance.set(snapshot);
    try {
      return action.get();
    } finally {
      issuance.remove();
    }
  }

  <T> T issuingUser(
      ClientStore.Snapshot snapshot,
      com.ken.platform.identity.service.login.PasswordLoginService.Identity user,
      Supplier<T> action) {
    if (userIssuance.get() != null) throw new IllegalStateException("不允许嵌套用户协议签发");
    userIssuance.set(user);
    try {
      return issuing(snapshot, action);
    } finally {
      userIssuance.remove();
    }
  }

  @Override
  public void save(OAuth2Authorization value) {
    requireTransaction();
    var existing = official.findById(value.getId());
    var snapshot = issuance.get();
    if (snapshot != null) {
      var current = clients.byId(value.getRegisteredClientId());
      if (current == null
          || !snapshot.client().getId().equals(value.getRegisteredClientId())
          || current.policy().version() != snapshot.policy().version()
          || !current.client().getScopes().containsAll(value.getAuthorizedScopes())) {
        throw new OAuth2AuthenticationException("invalid_scope");
      }
      if (existing == null) {
        value =
            OAuth2Authorization.from(value)
                .attribute(
                    AUDIENCES,
                    new java.util.ArrayList<>(
                        snapshot.policy().audiences().stream().sorted().toList()))
                .attribute(
                    TENANTS,
                    new java.util.ArrayList<>(
                        snapshot.policy().allowedTenantIds().stream()
                            .map(Object::toString)
                            .sorted()
                            .toList()))
                .attribute(
                    TYPE,
                    snapshot.policy().principalType().name().toLowerCase(java.util.Locale.ROOT))
                .attribute(VERSION, Long.toString(snapshot.policy().version()))
                .build();
        var user = userIssuance.get();
        if (user != null) {
          value =
              OAuth2Authorization.from(value)
                  .attribute(SESSION, user.sessionId().toString())
                  .attribute(GRANT_AT, java.time.Instant.now().toString())
                  .attribute(
                      TENANTS,
                      new java.util.ArrayList<>(
                          user.tenant() == null
                              ? List.<String>of()
                              : List.of(user.tenant().toString())))
                  .build();
        }
      }
    } else if (existing == null) {
      throw new IllegalStateException("新授权必须经过受控签发边界");
    }
    if (existing != null) {
      for (String key : BINDINGS) {
        if (!Objects.equals(existing.getAttribute(key), value.getAttribute(key))) {
          throw new IllegalStateException("不可改写历史授权绑定");
        }
      }
    }
    if (value.getAttribute(GRANT_AT) instanceof String started && value.getRefreshToken() != null) {
      var token = value.getRefreshToken().getToken();
      var limit =
          java.time.Instant.parse(started)
              .plusSeconds(8 * 3600)
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      if (token.getExpiresAt().isAfter(limit)) {
        var metadata = value.getRefreshToken().getMetadata();
        value =
            OAuth2Authorization.from(value)
                .token(
                    new org.springframework.security.oauth2.core.OAuth2RefreshToken(
                        token.getTokenValue(), token.getIssuedAt(), limit),
                    target -> target.putAll(metadata))
                .build();
      }
    }
    official.save(value);
  }

  @Override
  public void remove(OAuth2Authorization value) {
    var user = userIssuance.get();
    if (user == null || !user.sessionId().toString().equals(value.getAttribute(SESSION)))
      throw new UnsupportedOperationException("在线协议只可撤销当前受控授权，历史清理另走保留策略");
    requireTransaction();
    save(revoked(value));
  }

  public void revokeForSession(java.util.UUID session) {
    requireTransaction();
    for (String id : official.lockSessionAuthorizations(session)) {
      var value = official.findById(id);
      if (value != null && value.getAttribute(REVOKED) == null) save(revoked(value));
    }
  }

  public int revokeForApplication(
      com.ken.platform.identity.service.login.PasswordLoginService.Identity owner, String client) {
    requireTransaction();
    int changed = 0;
    for (String id : official.lockApplicationAuthorizations(owner, client)) {
      var value = official.findById(id);
      if (value != null && value.getAttribute(REVOKED) == null) {
        save(revoked(value));
        changed++;
      }
    }
    return changed;
  }

  private static OAuth2Authorization revoked(OAuth2Authorization value) {
    var builder =
        OAuth2Authorization.from(value)
            .attribute(REVOKED, java.time.Instant.now().toString())
            .attributes(values -> values.remove("state"));
    if (value.getAccessToken() != null) builder.invalidate(value.getAccessToken().getToken());
    if (value.getRefreshToken() != null) builder.invalidate(value.getRefreshToken().getToken());
    var code =
        value.getToken(
            org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class);
    if (code != null) builder.invalidate(code.getToken());
    return builder.build();
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return official.findById(id);
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    return official.findByToken(token, type);
  }

  OAuth2Authorization observeByToken(String token, OAuth2TokenType type) {
    return official.observeByToken(token, type);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("授权写入必须位于协议事务内");
    }
  }
}
