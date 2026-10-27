package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用户协议的事务与不可变登录绑定；PKCE、精确回调、code 消费和 consent 仍由官方 Provider 执行。 */
public final class UserProtocolProvider implements AuthenticationProvider {
  static final class AuditedAuthorizationFailure
      extends OAuth2AuthorizationCodeRequestAuthenticationException
      implements AuditedProtocolFailure {
    AuditedAuthorizationFailure(
        String code, OAuth2AuthorizationCodeRequestAuthenticationToken request) {
      super(new OAuth2Error(code), request);
    }
  }

  private record Outcome(Authentication value, OAuth2AuthenticationException failure) {}

  private final AuthenticationProvider delegate;
  private final ClientStore clients;
  private final ProtocolAuthorizations authorizations;
  private final PasswordLoginService logins;
  private final LoginStore sessions;
  private final SecurityAuditRepository audits;
  private final TransactionTemplate transactions;
  private final TransactionTemplate events;

  public UserProtocolProvider(
      AuthenticationProvider delegate,
      ClientStore clients,
      ProtocolAuthorizations authorizations,
      PasswordLoginService logins,
      LoginStore sessions,
      SecurityAuditRepository audits,
      PlatformTransactionManager manager) {
    this.delegate = delegate;
    this.clients = clients;
    this.authorizations = authorizations;
    this.logins = logins;
    this.sessions = sessions;
    this.audits = audits;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
    events = new TransactionTemplate(manager);
    events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    events.setTimeout(5);
  }

  public static boolean userProvider(AuthenticationProvider provider) {
    return provider.supports(OAuth2AuthorizationCodeRequestAuthenticationToken.class)
        || provider.supports(OAuth2AuthorizationConsentAuthenticationToken.class)
        || provider.supports(OAuth2AuthorizationCodeAuthenticationToken.class)
        || provider.supports(OAuth2RefreshTokenAuthenticationToken.class);
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication input) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("用户协议入口必须拥有事务边界");
    boolean interactive =
        input instanceof OAuth2AuthorizationCodeRequestAuthenticationToken
            || input instanceof OAuth2AuthorizationConsentAuthenticationToken;
    String action = interactive ? "identity.oauth.authorization" : "identity.oauth.token";
    String request =
        CallContexts.current() == null
            ? UUID.randomUUID().toString()
            : CallContexts.current().requestId();
    Identity[] verified = {null};
    String[] clientId = {null};
    Outcome outcome;
    try {
      outcome =
          transactions.execute(
              status -> {
                if (logins == null || sessions == null) throw error("unauthorized_client");
                sessions.lockCatalog();
                var observed = locate(input);
                String id;
                Identity identity;
                if (input
                    instanceof OAuth2AuthorizationCodeRequestAuthenticationToken authorization) {
                  var current = clients.byClientId(authorization.getClientId());
                  if (current == null) throw error("invalid_request");
                  id = current.client().getId();
                  identity = identity((Authentication) authorization.getPrincipal());
                } else if (input instanceof OAuth2AuthorizationConsentAuthenticationToken consent) {
                  if (observed == null) throw error("invalid_request");
                  id = observed.getRegisteredClientId();
                  identity = identity((Authentication) consent.getPrincipal());
                } else {
                  if (!(input.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                      || !client.isAuthenticated()
                      || client.getRegisteredClient() == null) throw error("invalid_client");
                  if (observed == null
                      || !client
                          .getRegisteredClient()
                          .getId()
                          .equals(observed.getRegisteredClientId())) throw error("invalid_grant");
                  id = observed.getRegisteredClientId();
                  identity = identity(observed.getAttribute(Principal.class.getName()));
                }
                var current = clients.lockSnapshot(id);
                if (current == null
                    || current.policy().status() != Status.ACTIVE
                    || current.policy().principalType() == PrincipalType.SERVICE)
                  throw error("unauthorized_client");
                if (!interactive) {
                  var client = (OAuth2ClientAuthenticationToken) input.getPrincipal();
                  if (!Objects.equals(
                          current.client().getClientSecret(),
                          client.getRegisteredClient().getClientSecret())
                      || !current
                          .client()
                          .getClientAuthenticationMethods()
                          .contains(client.getClientAuthenticationMethod()))
                    throw error("invalid_client");
                  var grant =
                      (org.springframework.security.oauth2.server.authorization.authentication
                              .OAuth2AuthorizationGrantAuthenticationToken)
                          input;
                  if (grant.getAdditionalParameters().keySet().stream()
                      .anyMatch(
                          Set.of("tenant_id", "tenant_code", "audience", "resource")::contains))
                    throw error("invalid_request");
                }
                if (identity.restricted()
                    || identity.realm() == LoginRealm.PLATFORM
                        && current.policy().principalType() != PrincipalType.PLATFORM
                    || identity.realm() == LoginRealm.TENANT
                        && (current.policy().principalType() != PrincipalType.USER
                            || !current.policy().allowedTenantIds().contains(identity.tenant()))
                    || clients.audienceCount(current.policy().audiences())
                        != current.policy().audiences().size()) throw error("unauthorized_client");
                if (input
                    instanceof OAuth2AuthorizationCodeRequestAuthenticationToken authorization) {
                  var parameters = authorization.getAdditionalParameters();
                  if (identity.realm() == LoginRealm.TENANT) {
                    Object code = parameters.get("tenant_code");
                    if (!(code instanceof String tenantCode)
                        || !identity.tenant().equals(sessions.resolveTenant(tenantCode)))
                      throw error("invalid_request");
                  } else if (parameters.containsKey("tenant_code")) throw error("invalid_request");
                  if (!"S256".equals(parameters.get("code_challenge_method"))
                      || !(parameters.get("code_challenge") instanceof String challenge)
                      || !challenge.matches("[A-Za-z0-9_-]{43}")
                      || authorization.getState() == null
                      || authorization.getState().isBlank()
                      || authorization.getState().length() > 1024
                      || authorization.getScopes().contains("openid")
                          && (!(parameters.get("nonce") instanceof String nonce)
                              || nonce.isBlank()
                              || nonce.length() > 256)
                      || parameters.keySet().stream()
                          .anyMatch(
                              Set.of("tenant_id", "audience", "resource", "request_uri", "request")
                                  ::contains)) throw error("invalid_request");
                } else {
                  if (!identity
                          .sessionId()
                          .toString()
                          .equals(observed.getAttribute(ProtocolAuthorizations.SESSION))
                      || observed.getAttribute(ProtocolAuthorizations.REVOKED) != null)
                    throw error("invalid_grant");
                  if (input instanceof OAuth2RefreshTokenAuthenticationToken refresh) {
                    if (!current
                            .client()
                            .getAuthorizationGrantTypes()
                            .contains(
                                org.springframework.security.oauth2.core.AuthorizationGrantType
                                    .REFRESH_TOKEN)
                        || current
                            .client()
                            .getClientAuthenticationMethods()
                            .contains(
                                org.springframework.security.oauth2.core.ClientAuthenticationMethod
                                    .NONE)) throw error("unauthorized_client");
                    String started = observed.getAttribute(ProtocolAuthorizations.GRANT_AT);
                    if (started == null
                        || !Instant.now().isBefore(Instant.parse(started).plusSeconds(8 * 3600)))
                      throw error("invalid_grant");
                    if (refresh.getAdditionalParameters().keySet().stream()
                        .anyMatch(
                            Set.of("tenant_id", "tenant_code", "audience", "resource")::contains))
                      throw error("invalid_request");
                  }
                }
                if (logins.checkProtocolSession(identity) == null)
                  return new Outcome(null, error("invalid_grant")); // 提交到期撤销后再返回错误。
                verified[0] = identity;
                clientId[0] = current.client().getClientId();
                return authorizations.issuingUser(
                    current,
                    identity,
                    () -> {
                      try {
                        Authentication result = delegate.authenticate(input);
                        audit(action, "SUCCESS", identity, clientId[0], null, request);
                        return new Outcome(result, null);
                      } catch (OAuth2AuthenticationException rejected) {
                        // 官方 code 重放/consent 拒绝已写入的撤销必须提交；运行/存储异常仍回滚。
                        return new Outcome(null, rejected);
                      }
                    });
              });
    } catch (RuntimeException failure) {
      String code =
          failure instanceof OAuth2AuthenticationException known
              ? known.getError().getErrorCode()
              : failure instanceof org.springframework.dao.DataAccessException
                      || failure instanceof com.ken.platform.foundation.http.PlatformException
                  ? "temporarily_unavailable"
                  : "server_error";
      outcome = new Outcome(null, error(code));
    }
    if (outcome.failure() == null) return outcome.value();
    String code = outcome.failure().getError().getErrorCode();
    if (!code.matches("[a-z_]{1,64}")) code = "server_error";
    String safe = code;
    try {
      events.execute(
          status -> {
            audit(action, "FAILURE", verified[0], clientId[0], safe, request);
            return null;
          });
    } catch (RuntimeException unavailable) {
      code = "temporarily_unavailable";
    }
    if (interactive) {
      if (outcome.failure()
              instanceof OAuth2AuthorizationCodeRequestAuthenticationException original
          && code.equals(original.getError().getErrorCode()))
        throw new AuditedAuthorizationFailure(
            code, original.getAuthorizationCodeRequestAuthentication());
      throw new AuditedAuthorizationFailure(code, null);
    }
    throw new ProtocolProvider.AuditedFailure(code);
  }

  private OAuth2Authorization locate(Authentication input) {
    if (input instanceof OAuth2AuthorizationConsentAuthenticationToken consent)
      return authorizations.observeByToken(consent.getState(), new OAuth2TokenType("state"));
    if (input instanceof OAuth2AuthorizationCodeAuthenticationToken code)
      return authorizations.observeByToken(code.getCode(), new OAuth2TokenType("code"));
    if (input instanceof OAuth2RefreshTokenAuthenticationToken refresh)
      return authorizations.observeByToken(
          refresh.getRefreshToken(), OAuth2TokenType.REFRESH_TOKEN);
    return null;
  }

  private static Identity identity(Authentication principal) {
    if (principal == null
        || !principal.isAuthenticated()
        || !(principal.getPrincipal() instanceof Identity identity)) throw error("invalid_grant");
    return identity;
  }

  private void audit(
      String action, String result, Identity user, String client, String code, String request) {
    audits.userProtocolEvent(
        action,
        result,
        user == null ? null : user.user(),
        user == null ? "ANONYMOUS" : user.realm().name(),
        user == null ? null : user.tenant(),
        client,
        code,
        request);
  }

  private static OAuth2AuthenticationException error(String code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code));
  }
}
