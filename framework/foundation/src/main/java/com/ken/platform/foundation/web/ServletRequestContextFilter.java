package com.ken.platform.foundation.web;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ServletRequestContextFilter extends OncePerRequestFilter implements Ordered {
  private final Clock clock;
  private final RuntimeParameters parameters;

  public ServletRequestContextFilter(Clock clock, RuntimeParameters parameters) {
    this.clock = clock;
    this.parameters = parameters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CallContext context =
        RequestContexts.incoming(
            request.getHeader("X-Request-Id"),
            request.getHeader("traceparent"),
            request.getHeader("X-Request-Deadline"),
            clock,
            parameters.responseBudgetSeconds());
    request.setAttribute(RequestContexts.ATTRIBUTE, context);
    response.setHeader("X-Request-Id", context.requestId());
    try (var scope = CallContexts.bind(context)) {
      chain.doFilter(request, response);
    } catch (PlatformException error) {
      writeError(response, context, error);
    } catch (ServletException wrapped) {
      if (wrapped.getCause() instanceof PlatformException error) {
        writeError(response, context, error);
      } else {
        throw wrapped;
      }
    }
  }

  private static void writeError(
      HttpServletResponse response, CallContext context, PlatformException error)
      throws IOException {
    if (response.isCommitted()) {
      throw error;
    }
    response.setStatus(error.status());
    response.setContentType("application/json");
    response.getOutputStream().write(RequestContexts.error(error, context));
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
