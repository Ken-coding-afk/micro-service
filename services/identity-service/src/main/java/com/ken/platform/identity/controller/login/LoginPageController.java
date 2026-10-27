package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.SessionCleanup;
import com.ken.platform.identity.service.security.SecurityPolicies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public final class LoginPageController {
  private final PasswordLoginService service;
  private final SessionCleanup cleanup;
  private final SecurityPolicies policies;
  private final LoginFailures failures;
  private final org.springframework.beans.factory.ObjectProvider<
          com.ken.platform.identity.config.FederationProviders>
      external;

  public LoginPageController(
      PasswordLoginService service,
      SessionCleanup cleanup,
      SecurityPolicies policies,
      LoginFailures failures,
      org.springframework.beans.factory.ObjectProvider<
              com.ken.platform.identity.config.FederationProviders>
          external) {
    this.service = service;
    this.cleanup = cleanup;
    this.policies = policies;
    this.failures = failures;
    this.external = external;
  }

  @RequestMapping(
      value = "/identity/login",
      method = {RequestMethod.GET, RequestMethod.POST})
  String login(HttpServletRequest request, HttpServletResponse response, Model model) {
    response.setHeader("Cache-Control", "no-store");
    Object error = request.getAttribute("loginError");
    model.addAttribute("loginError", error);
    var providers = external.getIfAvailable();
    model.addAttribute("providers", providers == null ? java.util.List.of() : providers.ids());
    Object status = request.getAttribute("loginStatus");
    if (status instanceof Integer value) response.setStatus(value);
    String realm = request.getParameter("realm");
    model.addAttribute(
        "realm",
        Set.of("TENANT", "PLATFORM").contains(realm == null ? "" : realm) ? realm : "TENANT");
    String tenant = request.getParameter("tenant_code");
    model.addAttribute(
        "tenantCode", tenant != null && tenant.matches("[a-z][a-z0-9-]{0,63}") ? tenant : "");
    return "identity/login";
  }

  @GetMapping("/identity/password/initial")
  String initial(Authentication auth, Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("minimum", policies.current(identity(auth).tenant()).passwordMinChars());
    return "identity/initial-password";
  }

  @PostMapping("/identity/password/initial")
  String change(
      @RequestParam(required = false) String password,
      @RequestParam(required = false) String confirmation,
      Authentication auth,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    var owner = identity(auth);
    boolean changed = false;
    response.setHeader("Cache-Control", "no-store");
    try {
      if (!Objects.equals(password, confirmation)) {
        if (!failures.recordInitial(owner, "PASSWORD_CONFIRM_MISMATCH"))
          throw new PlatformException("SECURITY_AUDIT_UNAVAILABLE", "安全审计暂不可用", 503);
        throw new PlatformException("PASSWORD_CONFIRM_MISMATCH", "两次密码不一致", 400);
      }
      service.changeInitialPassword(password);
      changed = true;
      var pending = pendingAuthorization(request, response);
      cleanup.clean(owner.sessionId());
      var session = request.getSession(false);
      if (session != null) session.invalidate();
      if (pending != null) {
        // 只迁移框架已保存的授权请求；不迁移 Authentication/CSRF 或旧 SSO 绑定。
        request.getSession(true).setAttribute("SPRING_SECURITY_SAVED_REQUEST", pending);
        String location = "redirect:/identity/login?changed&realm=" + owner.realm().name();
        String[] tenant = pending.getParameterValues("tenant_code");
        if (owner.tenant() != null
            && tenant != null
            && tenant.length == 1
            && tenant[0].matches("[a-z][a-z0-9-]{0,63}")) location += "&tenant_code=" + tenant[0];
        return location;
      }
      return "redirect:/identity/login?changed";
    } catch (PlatformException rejected) {
      response.setStatus(rejected.status());
      if (changed) {
        model.addAttribute("loginError", "PASSWORD_CHANGED_CLEANUP_PENDING");
        model.addAttribute("realm", owner.realm().name());
        model.addAttribute("tenantCode", "");
        return "identity/login";
      }
      model.addAttribute("passwordError", rejected.code());
      model.addAttribute("minimum", policies.current(owner.tenant()).passwordMinChars());
      return "identity/initial-password";
    }
  }

  private static DefaultSavedRequest pendingAuthorization(
      HttpServletRequest request, HttpServletResponse response) {
    var pending = new HttpSessionRequestCache().getRequest(request, response);
    if (!(pending instanceof DefaultSavedRequest saved)
        || !saved.getMethod().equals("GET")
        || !saved.getRequestURI().equals(request.getContextPath() + "/oauth2/authorize"))
      return null;
    String query = saved.getQueryString();
    return query != null && query.length() <= 8192 && !query.contains("\r") && !query.contains("\n")
        ? saved
        : null;
  }

  @GetMapping("/identity/session")
  String session(Authentication auth, HttpServletResponse response, Model model) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("platform", identity(auth).tenant() == null);
    model.addAttribute(
        "externalEnabled", external.getIfAvailable() != null && identity(auth).tenant() != null);
    return "identity/session";
  }

  private static PasswordLoginService.Identity identity(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof PasswordLoginService.Identity value))
      throw new PlatformException("INVALID_CREDENTIALS", "请重新登录", 401);
    return value;
  }
}
