package com.ken.platform.identity.controller.management;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.controller.support.IdentityApiResponses;
import com.ken.platform.identity.service.audit.ManagementAudits;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.ken.platform.identity.controller.management")
public final class IdentityManagementErrors {
  private final ManagementAudits audits;

  public IdentityManagementErrors(ManagementAudits audits) {
    this.audits = audits;
  }

  @ExceptionHandler(PlatformException.class)
  ResponseEntity<Map<String, Object>> known(PlatformException failure) {
    if (failure.code().equals("INVALID_ARGUMENT")) {
      var rejected = failure;
      try {
        audits.attempt(
            ManagementAudits.Action.API_REQUEST,
            null,
            () -> {
              throw rejected;
            });
      } catch (PlatformException audited) {
        failure = audited;
      }
    }
    return IdentityApiResponses.response(
        failure.status(), failure.code(), failure.getMessage(), null);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Map<String, Object>> malformed(Exception ignored) {
    return known(new PlatformException("INVALID_ARGUMENT", "请求字段、类型或版本不合法", 400));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, Object>> unavailable(DataAccessException ignored) {
    return IdentityApiResponses.response(503, "IDENTITY_STORAGE_UNAVAILABLE", "身份存储暂不可用", null);
  }
}
