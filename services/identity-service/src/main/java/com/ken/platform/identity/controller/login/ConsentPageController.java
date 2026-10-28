package com.ken.platform.identity.controller.login;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** 只呈现待同意权限；CSRF、state、主体绑定及授权写入仍由既有过滤器和 SAS 校验。 */
@Controller
public final class ConsentPageController {
  private final RegisteredClientRepository clients;
  private final OAuth2AuthorizationConsentService consents;

  public ConsentPageController(
      RegisteredClientRepository clients, OAuth2AuthorizationConsentService consents) {
    this.clients = clients;
    this.consents = consents;
  }

  @GetMapping("/identity/consent")
  String consent(
      @RequestParam("client_id") String clientId,
      @RequestParam String scope,
      @RequestParam String state,
      Authentication authentication,
      Model model) {
    var client = clients.findByClientId(clientId);
    var requested = new LinkedHashSet<>(Arrays.asList(scope.split(" ")));
    if (client == null || state.isBlank() || !client.getScopes().containsAll(requested))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    requested.remove("openid");
    var consent = consents.findById(client.getId(), authentication.getName());
    var approved = consent == null ? java.util.Set.<String>of() : consent.getScopes();
    List<String> pending = requested.stream().filter(value -> !approved.contains(value)).toList();
    model.addAttribute("clientId", clientId);
    model.addAttribute("clientName", client.getClientName());
    model.addAttribute("state", state);
    model.addAttribute("scopes", pending);
    model.addAttribute("approvedScopes", requested.stream().filter(approved::contains).toList());
    return "identity/consent";
  }
}
