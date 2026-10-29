package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.login.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public final class PasswordResetPageController {
  private final PasswordResetService resets;
  private final TrustedClientAddress source;

  public PasswordResetPageController(PasswordResetService resets, TrustedClientAddress source) {
    this.resets = resets;
    this.source = source;
  }

  @GetMapping("/identity/password/reset")
  String page(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return "identity/password-reset";
  }

  @PostMapping("/identity/password/reset")
  String consume(
      @RequestParam(defaultValue = "") String token,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "") String confirmation,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    response.setHeader("Cache-Control", "no-store");
    try {
      if (token.length() > 128 || password.length() > 256 || confirmation.length() > 256)
        throw new PlatformException("INVALID_ARGUMENT", "输入超出长度限制", 400);
      resets.consume(token, password, confirmation, source.resolve(request));
      model.addAttribute("completed", true);
    } catch (PlatformException failure) {
      response.setStatus(failure.status());
      model.addAttribute("error", failure.getMessage());
    }
    return "identity/password-reset";
  }
}
