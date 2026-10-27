package com.ken.platform.foundation.resilience;

import com.ken.platform.foundation.http.HttpProtection;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** 普通请求在返回时释放；异步请求持有到 onComplete，超时通知本身不代表工作结束。 */
public final class ServletAdmissionFilter extends OncePerRequestFilter implements Ordered {
  private final HttpProtection protection;

  public ServletAdmissionFilter(HttpProtection protection) {
    this.protection = protection;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/v1/") && !path.startsWith("/internal/v1/")) {
      chain.doFilter(request, response);
      return;
    }
    HttpProtection.Lease lease = protection.enter("http.inbound");
    HttpServletRequestWrapper wrapper =
        new HttpServletRequestWrapper(request) {
          private AsyncContext listen(AsyncContext context) {
            context.addListener(
                new AsyncListener() {
                  public void onComplete(AsyncEvent event) {
                    lease.close();
                  }

                  public void onTimeout(AsyncEvent event) {}

                  public void onError(AsyncEvent event) {}

                  public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                  }
                });
            return context;
          }

          @Override
          public AsyncContext startAsync() {
            return listen(super.startAsync());
          }

          @Override
          public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
            return listen(super.startAsync(req, res));
          }
        };
    try {
      chain.doFilter(wrapper, response);
    } finally {
      if (!request.isAsyncStarted()) lease.close();
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
