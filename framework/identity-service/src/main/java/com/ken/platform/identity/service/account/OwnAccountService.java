package com.ken.platform.identity.service.account;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import com.ken.platform.identity.service.oauth.ApplicationConsentService;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 将本地已验证的身份管理授权还原为其原 SSO 主体，复用本人用例的事务与实时检查。 */
@Service
public final class OwnAccountService {
  private final PasswordLoginService logins;
  private final ApplicationConsentService consents;
  private final ManagementAudits audits;

  public OwnAccountService(
      PasswordLoginService logins, ApplicationConsentService consents, ManagementAudits audits) {
    this.logins = logins;
    this.consents = consents;
    this.audits = audits;
  }

  public List<PasswordLoginService.SessionSummary> sessions(int page, int size) {
    return execute(
        () -> {
          if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new PlatformException("INVALID_ARGUMENT", "分页范围不合法", 400);
          return logins.ownSessions(page * size, size);
        });
  }

  public void revokeSession(UUID id) {
    execute(
        () -> {
          logins.revokeOwnSession(id);
          return null;
        });
  }

  public int signOutAll() {
    return execute(logins::signOutAllOwnSessions);
  }

  public List<ApplicationConsentService.ConsentSummary> consents(int page, int size) {
    return execute(
        () -> {
          if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new PlatformException("INVALID_ARGUMENT", "分页范围不合法", 400);
          return consents.ownConsents(page * size, size);
        });
  }

  public void revokeConsent(String clientId) {
    execute(
        () -> {
          consents.revokeOwnConsent(clientId);
          return null;
        });
  }

  public void changePassword(String current, String next) {
    execute(
        () -> {
          logins.changeOwnPassword(current, next);
          return null;
        });
  }

  public PasswordLoginService.ProfileView profile() {
    return execute(logins::ownProfile);
  }

  public PasswordLoginService.ProfileView updateProfile(long version, String displayName) {
    return execute(() -> logins.updateOwnProfile(version, displayName));
  }

  private <T> T execute(Supplier<T> operation) {
    Identity owner =
        audits.attempt(ManagementAudits.Action.API_REQUEST, null, OwnAccountService::owner);
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return operation.get();
    } finally {
      // 撤销/改密用例会 clearContext；原 Bearer 请求的清理仍由资源过滤链负责。
      SecurityContextHolder.setContext(previous);
    }
  }

  private static Identity owner() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof CurrentPrincipal principal)
        || !principal.scopes().contains("profile")
        || !(principal.getAttribute(UserTokenService.OWN_IDENTITY) instanceof Identity owner)
        || owner.restricted()
        || !principal.subject().equals(owner.user().toString())
        || !Objects.equals(principal.tenantId(), owner.tenant())
        || (owner.realm() == LoginRealm.PLATFORM
            ? !principal.principalType().equals("platform")
                || !principal.clientId().equals("identity-admin-platform")
            : !principal.principalType().equals("user")
                || !principal.clientId().equals("identity-admin-tenant")))
      throw new PlatformException("FORBIDDEN", "请从身份管理入口登录个人账号", 403);
    if (!(principal.getAttribute(UserTokenService.APPLICATION_VIEW)
            instanceof UserTokenService.ApplicationView view)
        || !view.audiences().containsKey("identity-service"))
      throw new PlatformException("FORBIDDEN", "当前应用没有身份服务访问范围", 403);
    return owner;
  }
}
