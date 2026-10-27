package com.ken.platform.identity.controller.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 放在 Spring Session 之外；会话依赖错误不能退化为匿名或返回原始异常。 */
public final class SessionFailureFilter extends OncePerRequestFilter implements Ordered {
  @Override
  public int getOrder() {
    return SessionRepositoryFilter.DEFAULT_ORDER - 1;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (org.springframework.dao.DataAccessException
        | SerializationException
        | com.ken.platform.foundation.http.PlatformException unavailable) {
      if (response.isCommitted()) throw new ServletException("会话依赖当前不可用");
      response.resetBuffer();
      response.setStatus(
          unavailable instanceof com.ken.platform.foundation.http.PlatformException known
              ? known.status()
              : 503);
      response.setContentType("text/html;charset=UTF-8");
      response.setHeader("Cache-Control", "no-store");
      response
          .getWriter()
          .write(
              "<!doctype html><html lang=zh-CN><meta charset=utf-8><title>服务暂不可用</title><p>登录会话暂不可用，请稍后重试。</p></html>");
    }
  }
}
