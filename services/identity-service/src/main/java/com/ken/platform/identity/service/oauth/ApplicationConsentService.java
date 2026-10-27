package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.oauth.OwnApplicationStore;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 本人应用同意管理；仅使用认证 SSO 主体，协议授权保留为撤销历史。 */
@Service
public final class ApplicationConsentService {
  public record ConsentSummary(
      @com.fasterxml.jackson.annotation.JsonProperty("client_id") String clientId,
      String name,
      String status,
      List<String> scopes) {
    public ConsentSummary {
      scopes = List.copyOf(scopes);
    }
  }

  private record Result<T>(boolean active, T value) {}

  private final OwnApplicationStore store;
  private final LoginStore sessions;
  private final PasswordLoginService logins;
  private final ClientStore clients;
  private final ProtocolAuthorizations authorizations;
  private final OAuth2AuthorizationConsentService consents;
  private final SecurityAuditRepository audits;
  private final TransactionTemplate transactions;
  private final TransactionTemplate events;

  public ApplicationConsentService(
      OwnApplicationStore store,
      LoginStore sessions,
      PasswordLoginService logins,
      ClientStore clients,
      ProtocolAuthorizations authorizations,
      OAuth2AuthorizationConsentService consents,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager) {
    this.store = store;
    this.sessions = sessions;
    this.logins = logins;
    this.clients = clients;
    this.authorizations = authorizations;
    this.consents = consents;
    this.audits = audits;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
    events = new TransactionTemplate(manager);
    events.setTimeout(5);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public List<ConsentSummary> ownConsents(int offset, int limit) {
    return execute(
        "identity.consents.read",
        null,
        (owner, ignored) -> {
          if (offset < 0 || limit < 1 || limit > 100)
            throw new PlatformException("INVALID_PAGINATION", "分页范围不合法", 400);
          return store.consents(owner, offset, limit).stream()
              .map(
                  row -> {
                    var consent = consents.findById(row.id(), owner.user().toString());
                    if (consent == null) throw unavailable();
                    return new ConsentSummary(
                        row.clientId(),
                        row.name(),
                        row.status(),
                        consent.getScopes().stream().sorted().toList());
                  })
              .toList();
        });
  }

  /** 撤销该用户此客户端的全部授权和同意，不撤销 SSO 或别的应用。 */
  public void revokeOwnConsent(String clientId) {
    if (clientId == null) clientId = ""; // 保持写事务分支，并由同一输入校验拒绝。
    execute(
        "identity.consent.revoked",
        clientId,
        (owner, client) -> {
          var consent = consents.findById(client.client().getId(), owner.user().toString());
          int changed = authorizations.revokeForApplication(owner, client.client().getId());
          if (consent != null) consents.remove(consent);
          if (consent != null || changed > 0)
            store.audit(owner, client.client().getClientId(), changed, consent != null, request());
          return null;
        });
  }

  private <T> T execute(
      String action, String clientId, BiFunction<Identity, ClientStore.Snapshot, T> operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("本人应用管理必须拥有事务边界");
    Identity[] verified = {null};
    String request = request();
    var context = CallContexts.current();
    try (var ignored =
        CallContexts.bind(
            context == null
                ? new com.ken.platform.foundation.http.CallContext(
                    request, null, null, null, null, null)
                : context)) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !authentication.isAuthenticated()
          || !(authentication.getPrincipal() instanceof Identity owner)) throw invalid();
      Result<T> result =
          transactions.execute(
              status -> {
                if (clientId == null) sessions.lockCatalog();
                else sessions.lockSessionManagement();
                ClientStore.Snapshot snapshot = null;
                if (clientId != null) {
                  if (!clientId.matches("[A-Za-z0-9._:-]{1,128}"))
                    throw new PlatformException("INVALID_CLIENT_ID", "应用标识不合法", 400);
                  var observed = clients.byClientId(clientId);
                  if (observed != null) snapshot = clients.lockSnapshot(observed.client().getId());
                }
                var current = logins.checkProtocolSession(owner);
                if (current == null) return new Result<>(false, null);
                verified[0] = current;
                if (clientId != null
                    && (snapshot == null
                        || snapshot.policy().principalType()
                            != (current.realm() == LoginRealm.PLATFORM
                                ? PrincipalType.PLATFORM
                                : PrincipalType.USER)))
                  throw new PlatformException("APPLICATION_NOT_FOUND", "应用不存在", 404);
                return new Result<>(true, operation.apply(current, snapshot));
              });
      if (!result.active()) throw invalid(); // 到期撤销已提交，不能在回滚后才返回认证失败。
      return result.value();
    } catch (RuntimeException failure) {
      String code =
          failure instanceof PlatformException known
              ? known.code()
              : "APPLICATION_CONSENT_UNAVAILABLE";
      if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}"))
        code = "APPLICATION_CONSENT_UNAVAILABLE";
      String safe = code;
      try {
        events.execute(
            status -> {
              var owner = verified[0];
              audits.failure(
                  owner == null ? null : owner.user(),
                  owner == null ? "ANONYMOUS" : owner.realm().name(),
                  owner == null ? null : owner.tenant(),
                  action,
                  null,
                  request,
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .writeValueAsString(Map.of("error_code", safe)));
              return null;
            });
      } catch (RuntimeException unavailable) {
        throw new PlatformException("SECURITY_AUDIT_UNAVAILABLE", "安全审计暂不可用", 503);
      }
      if (failure instanceof PlatformException known) throw known;
      throw unavailable();
    }
  }

  private static String request() {
    return CallContexts.current() == null
        ? UUID.randomUUID().toString()
        : CallContexts.current().requestId();
  }

  private static PlatformException invalid() {
    return new PlatformException("INVALID_CREDENTIALS", "请重新登录", 401);
  }

  private static PlatformException unavailable() {
    return new PlatformException("APPLICATION_CONSENT_UNAVAILABLE", "应用授权暂不可用", 503);
  }
}
