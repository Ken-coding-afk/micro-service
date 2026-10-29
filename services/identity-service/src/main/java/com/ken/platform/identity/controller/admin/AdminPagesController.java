package com.ken.platform.identity.controller.admin;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 只把本产品已交付页面交给 Vue Router，API 和不存在的资源不返回首页。 */
@Controller
public final class AdminPagesController {
  @GetMapping("/identity/admin")
  String canonical() {
    return "redirect:/identity/admin/";
  }

  @GetMapping({
    "/identity/admin/",
    "/identity/admin/account",
    "/identity/admin/platform/tenants",
    "/identity/admin/platform/tenants/{managementTenant}/identity/users",
    "/identity/admin/platform/tenants/{managementTenant}/identity/roles",
    "/identity/admin/platform/tenants/{managementTenant}/identity/roles/{roleId}",
    "/identity/admin/platform/tenants/{managementTenant}/identity/groups",
    "/identity/admin/platform/permissions",
    "/identity/admin/platform/tenants/{managementTenant}/identity/sessions",
    "/identity/admin/platform/tenants/{managementTenant}/identity/audits",
    "/identity/admin/platform/audits",
    "/identity/admin/platform/monitoring",
    "/identity/admin/platform/admins",
    "/identity/admin/platform/clients",
    "/identity/admin/platform/security"
  })
  String account(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return "forward:/identity/admin/index.html";
  }
}
