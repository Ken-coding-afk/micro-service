package com.ken.platform.foundation.security;

import com.ken.platform.foundation.http.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Locale;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 已认证业务路由必须声明授权；内部用户请求再验证机器且保持原用户上下文。 */
public final class ResourceRequestGuard implements HandlerInterceptor {
  private final ResourceIntrospector introspector;

  public ResourceRequestGuard(ResourceIntrospector introspector) {
    this.introspector = introspector;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    try {
      if (!(handler instanceof HandlerMethod method)
          || !(request.getAttribute(ResourceContextFilter.PRINCIPAL)
              instanceof CurrentPrincipal principal)) throw forbidden();
      var required =
          AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequirePermission.class);
      if (required == null)
        required =
            AnnotatedElementUtils.findMergedAnnotation(
                method.getBeanType(), RequirePermission.class);
      if (required == null) throw forbidden();
      principal.require(
          required.scope(), required.value(), required.subject().name().toLowerCase(Locale.ROOT));
      boolean internal =
          request.getRequestURI().equals(request.getContextPath() + "/internal")
              || request.getRequestURI().startsWith(request.getContextPath() + "/internal/");
      var service =
          AnnotatedElementUtils.findMergedAnnotation(
              method.getMethod(), RequireServicePermission.class);
      if (service == null)
        service =
            AnnotatedElementUtils.findMergedAnnotation(
                method.getBeanType(), RequireServicePermission.class);
      if (service != null && !internal) throw forbidden();
      if (internal && !principal.principalType().equals("service")) {
        if (service == null) throw forbidden();
        var machine =
            (CurrentPrincipal) introspector.introspect(bearer(request, "X-Service-Authorization"));
        machine.require(service.scope(), service.value(), "service");
        if (principal.tenantId() != null
            && !machine.allowedTenantIds().contains(principal.tenantId())) throw forbidden();
      }
      return true;
    } catch (BadOpaqueTokenException invalid) {
      ResourceErrors.write(response, 401, "INVALID_CREDENTIALS");
      return false;
    } catch (OAuth2IntrospectionException unavailable) {
      ResourceErrors.write(response, 503, "IDENTITY_UNAVAILABLE");
      return false;
    } catch (PlatformException denied) {
      ResourceErrors.write(response, denied.status(), denied.code());
      return false;
    }
  }

  public static String bearer(HttpServletRequest request, String header) {
    var values = Collections.list(request.getHeaders(header));
    if (values.size() != 1 || !values.getFirst().matches("(?i)Bearer [A-Za-z0-9._~+/-]+=*"))
      throw CurrentPrincipal.invalid();
    return values.getFirst().substring(7);
  }

  private static PlatformException forbidden() {
    return new PlatformException("FORBIDDEN", "资源接口未声明授权或当前身份不在管理范围内", 403);
  }
}
