package com.ken.platform.identity.controller.login;

import com.ken.platform.identity.config.FederationProviders;
import com.ken.platform.identity.service.login.ExternalIdentities;
import com.ken.platform.identity.service.login.PasswordLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "platform.federation.enabled",
    havingValue = "true")
public final class FederationController {
  private final ExternalIdentities identities;
  private final FederationProviders providers;
  private final PasswordLoginService logins;
  private final Clock clock;
  private final TrustedClientAddress source;
  private final LoginFailures failures;

  public FederationController(
      ExternalIdentities identities,
      FederationProviders providers,
      PasswordLoginService logins,
      Clock clock,
      TrustedClientAddress source,
      LoginFailures failures) {
    this.failures = failures;
    this.source = source;
    this.identities = identities;
    this.providers = providers;
    this.logins = logins;
    this.clock = clock;
  }

  @GetMapping("/identity/external")
  String page(Model model, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("providers", providers.ids());
    model.addAttribute("bindings", identities.registrations());
    return "identity/external";
  }

  @PostMapping("/identity/external/start")
  String start(
      @RequestParam String provider,
      @RequestParam String tenant_code,
      @RequestParam String mode,
      HttpServletRequest request) {
    providers.require(provider, tenant_code);
    var tenant = identities.tenant(tenant_code, source.resolve(request));
    var intent = new LinkedHashMap<String, String>();
    intent.put("provider", provider);
    intent.put("tenant", tenant.toString());
    intent.put("tenant_code", tenant_code);
    intent.put("created", Long.toString(clock.millis()));
    if (mode.equals("BIND")) {
      logins.verifyBrowserBinding(request.getSession().getId());
      var owner = identities.bindingOwner();
      if (!tenant.equals(owner.tenant())) throw new IllegalArgumentException("不能跨租户绑定");
      intent.put("binding_session", owner.sessionId().toString());
    } else if (!mode.equals("LOGIN")) throw new IllegalArgumentException("外部登录模式无效");
    request.getSession(true).setAttribute(FederationRequests.INTENT, intent);
    return "redirect:/oauth2/authorization/" + provider;
  }

  @PostMapping("/identity/external/unlink")
  String unlink(@RequestParam String provider, HttpServletRequest request) {
    logins.verifyBrowserBinding(request.getSession().getId());
    identities.unlink(provider);
    return "redirect:/identity/external";
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    com.ken.platform.foundation.http.PlatformException.class
  })
  void invalid(RuntimeException error, HttpServletResponse response) throws java.io.IOException {
    response.setHeader("Cache-Control", "no-store");
    int status =
        error instanceof com.ken.platform.foundation.http.PlatformException known
            ? known.status()
            : 400;
    String code =
        error instanceof com.ken.platform.foundation.http.PlatformException known
            ? known.code()
            : "EXTERNAL_IDENTITY_REJECTED";
    if (!failures.recordExternal(code)) status = 503;
    response.sendError(status, "外部身份请求无效或需要重新登录");
  }
}
