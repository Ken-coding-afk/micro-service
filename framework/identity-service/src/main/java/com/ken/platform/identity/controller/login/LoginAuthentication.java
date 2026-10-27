package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.login.LoginProtection;
import com.ken.platform.identity.service.login.PasswordLoginService;
import java.net.InetAddress;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public final class LoginAuthentication implements AuthenticationProvider {
  public record Details(
      LoginRealm realm, String tenantCode, String rawUsername, InetAddress source) {}

  public static final class Failure extends AuthenticationException {
    private final String code;
    private final int status;
    private final long retry;
    private final boolean audited;

    public Failure(String code, int status, long retry, boolean audited) {
      super("登录请求未完成");
      this.code = code;
      this.status = status;
      this.retry = retry;
      this.audited = audited;
    }

    public String code() {
      return code;
    }

    public int status() {
      return status;
    }

    public long retry() {
      return retry;
    }

    public boolean audited() {
      return audited;
    }
  }

  private final PasswordLoginService service;

  public LoginAuthentication(PasswordLoginService service) {
    this.service = service;
  }

  @Override
  public boolean supports(Class<?> type) {
    return type == UsernamePasswordAuthenticationToken.class;
  }

  @Override
  public Authentication authenticate(Authentication incoming) {
    if (!(incoming.getDetails() instanceof Details details))
      throw new Failure("INVALID_CREDENTIALS", 401, 0, false);
    try {
      var identity =
          service.authenticate(
              details.realm(),
              details.tenantCode(),
              details.rawUsername(),
              incoming.getCredentials() instanceof String password ? password : null,
              details.source());
      // 不把表单参数、来源或密码作为 details 留在 Redis 会话中。
      var authenticated =
          UsernamePasswordAuthenticationToken.authenticated(
              identity,
              null,
              List.of(
                  org.springframework.security.core.authority.FactorGrantedAuthority.fromAuthority(
                      org.springframework.security.core.authority.FactorGrantedAuthority
                          .PASSWORD_AUTHORITY)));
      // ProviderManager 会在结果 details 为 null 时复制入站表单 details。
      authenticated.setDetails("PASSWORD");
      return authenticated;
    } catch (PlatformException rejected) {
      throw new Failure(
          rejected.code(),
          rejected.status(),
          rejected instanceof LoginProtection.Limited limited ? limited.retryAfterSeconds() : 0,
          true);
    }
  }
}
