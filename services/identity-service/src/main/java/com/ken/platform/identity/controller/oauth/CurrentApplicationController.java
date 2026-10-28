package com.ken.platform.identity.controller.oauth;

import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅投影已认证应用；请求参数不参与选择其他用户、客户端或 audience。 */
@RestController
public final class CurrentApplicationController {
  @GetMapping("/api/v1/identity/me")
  ResponseEntity<Map<String, Object>> currentApplication(Authentication authentication) {
    var principal = (OAuth2AuthenticatedPrincipal) authentication.getPrincipal();
    UserTokenService.ApplicationView view =
        principal.getAttribute(UserTokenService.APPLICATION_VIEW);
    if (view == null) throw new IllegalStateException("缺少服务器验证的应用投影");
    return IdentityApiResponses.ok(view);
  }
}
