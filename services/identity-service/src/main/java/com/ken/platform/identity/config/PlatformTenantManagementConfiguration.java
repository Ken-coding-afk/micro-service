package com.ken.platform.identity.config;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只解析已匹配管理方法的路径目标；不接受查询参数或请求头选择管理租户。 */
@Configuration(proxyBeanMethods = false)
public class PlatformTenantManagementConfiguration implements WebMvcConfigurer {
  private final TenantManagementAuthorization authorization;
  private final com.ken.platform.identity.service.audit.ManagementAudits audits;

  public PlatformTenantManagementConfiguration(
      TenantManagementAuthorization authorization,
      com.ken.platform.identity.service.audit.ManagementAudits audits) {
    this.authorization = authorization;
    this.audits = audits;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  jakarta.servlet.http.HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response,
                  Object handler) {
                if (!(handler instanceof org.springframework.web.method.HandlerMethod))
                  throw new PlatformException("NOT_FOUND", "管理接口不存在", 404);
                var variables =
                    (Map<?, ?>)
                        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                try {
                  UUID tenant = UUID.fromString((String) variables.get("managementTenant"));
                  audits.attempt(
                      com.ken.platform.identity.service.audit.ManagementAudits.Action.API_REQUEST,
                      null,
                      () -> {
                        authorization.selectTarget(request, tenant);
                        return null;
                      });
                } catch (IllegalArgumentException malformed) {
                  throw new PlatformException("INVALID_ARGUMENT", "目标租户标识不合法", 400);
                }
                return true;
              }
            })
        .addPathPatterns("/api/v1/identity/platform/tenants/*/identity/**");
  }
}
