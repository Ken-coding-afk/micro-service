package com.ken.platform.identity.controller.oauth;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.util.Map;
import org.springframework.http.CacheControl;
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
    var context = CallContexts.current();
    String requestId =
        context == null ? java.util.UUID.randomUUID().toString() : context.requestId();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .body(Map.of("code", "OK", "message", "success", "data", view, "request_id", requestId));
  }
}
