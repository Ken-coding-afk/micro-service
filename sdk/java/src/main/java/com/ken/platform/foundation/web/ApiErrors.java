package com.ken.platform.foundation.web;

import com.ken.platform.foundation.http.PlatformException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.ErrorResponse;

/** 普通 API 的安全默认错误；协议端点及具体业务 handler 不由此重新定义。 */
public final class ApiErrors {
  private ApiErrors() {}

  public static boolean accepts(String path) {
    return path.equals("/api")
        || path.equals("/internal")
        || path.equals("/bff")
        || path.startsWith("/api/")
        || path.startsWith("/internal/")
        || path.startsWith("/bff/");
  }

  public static PlatformException classify(Throwable error) {
    if (error instanceof PlatformException known) return known;
    if (error instanceof org.springframework.security.authentication.AuthenticationServiceException)
      return new PlatformException("IDENTITY_UNAVAILABLE", "身份内省暂不可用", 503);
    if (error instanceof org.springframework.security.core.AuthenticationException)
      return status(401);
    if (error instanceof org.springframework.security.access.AccessDeniedException)
      return status(403);
    return status(error instanceof ErrorResponse response ? response.getStatusCode().value() : 500);
  }

  public static PlatformException status(int status) {
    return switch (status) {
      case 400 -> new PlatformException("INVALID_REQUEST", "请求格式不合法", status);
      case 401 -> new PlatformException("INVALID_CREDENTIALS", "访问令牌无效", status);
      case 403 -> new PlatformException("FORBIDDEN", "无权访问", status);
      case 404 -> new PlatformException("NOT_FOUND", "资源不存在", status);
      case 405 -> new PlatformException("METHOD_NOT_ALLOWED", "请求方法不支持", status);
      case 415 -> new PlatformException("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不支持", status);
      case 429 -> new PlatformException("RATE_LIMITED", "请求过于频繁", status);
      case 504 -> new PlatformException("UPSTREAM_TIMEOUT", "调用预算已耗尽；写入结果可能需要核对", status);
      default ->
          new PlatformException(
              status >= 500 ? "INTERNAL_ERROR" : "INVALID_REQUEST",
              status >= 500 ? "服务暂时无法处理请求" : "请求格式不合法",
              status);
    };
  }

  static HttpHeaders headers(Throwable failure) {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl("no-store");
    if (failure instanceof ErrorResponse error) {
      for (var name : java.util.List.of("Allow", "Retry-After", "WWW-Authenticate"))
        if (error.getHeaders().containsHeader(name))
          headers.put(name, error.getHeaders().get(name));
    }
    return headers;
  }
}
