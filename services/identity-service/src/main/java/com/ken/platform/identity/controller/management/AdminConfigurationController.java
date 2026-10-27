package com.ken.platform.identity.controller.management;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.PlatformException;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 匿名启动页只获取非敏感展示参数；与账号、客户端和安全策略分开。 */
@RestController
@RequestMapping("/api/v1/identity/admin-config")
public final class AdminConfigurationController {
  public record View(
      @JsonProperty("project_display_name") String projectDisplayName,
      @JsonProperty("login_title") String loginTitle,
      @JsonProperty("admin_title") String adminTitle,
      @JsonProperty("logo_url") String logoUrl,
      @JsonProperty("api_major") int apiMajor) {}

  private final View view;

  public AdminConfigurationController(RuntimeParameters parameters, Environment environment) {
    var brand = parameters.identityAdminBrand();
    String project = environment.getRequiredProperty("PROJECT_CODE");
    if (!project.matches("[a-z][a-z0-9-]{0,63}"))
      throw new IllegalArgumentException("身份界面缺少有效项目绑定");
    view =
        new View(
            brand.projectDisplayName().isEmpty() ? project : brand.projectDisplayName(),
            "账号登录",
            "身份与访问管理",
            brand.logoUrl(),
            1);
  }

  @GetMapping
  ResponseEntity<Map<String, Object>> read(@RequestParam Map<String, String> query) {
    if (!query.isEmpty()) throw new PlatformException("INVALID_ARGUMENT", "品牌配置不接受查询覆盖", 400);
    return IdentityApiResponses.ok(view);
  }
}
