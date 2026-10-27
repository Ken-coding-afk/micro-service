package com.ken.platform.foundation.web;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** 放在业务 @ExceptionHandler 之后、Spring 默认错误页之前。 */
public final class ServletApiErrors implements HandlerExceptionResolver {
  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    if (!ApiErrors.accepts(request.getRequestURI()) || response.isCommitted()) return null;
    PlatformException error;
    if (failure instanceof org.springframework.web.bind.MethodArgumentNotValidException
        || failure
            instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
        || failure instanceof org.springframework.web.bind.ServletRequestBindingException) {
      error = new PlatformException("VALIDATION_ERROR", "请求参数不合法", 400);
    } else if (failure
        instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
      error = ApiErrors.status(400);
    } else error = ApiErrors.classify(failure);
    CallContext context = CallContexts.current();
    if (context == null) context = (CallContext) request.getAttribute(RequestContexts.ATTRIBUTE);
    if (context == null) context = new CallContext(null, null, null, null, null, null);
    try {
      response.setStatus(error.status());
      response.setContentType("application/json;charset=UTF-8");
      response.setHeader("X-Request-Id", context.requestId());
      ApiErrors.headers(failure)
          .forEach((key, values) -> values.forEach(value -> response.addHeader(key, value)));
      if (error.status() == 500)
        org.slf4j.LoggerFactory.getLogger(getClass())
            .error("请求处理失败 {}", ApiErrors.diagnostic(failure));
      response.getOutputStream().write(RequestContexts.error(error, context));
      return new ModelAndView();
    } catch (java.io.IOException failed) {
      return null;
    }
  }
}
