package com.ken.platform.foundation.discovery;

import java.util.List;
import org.springframework.context.SmartLifecycle;

/** HTTP 监听后、接受流量前注册；停机先注销，再由 Spring 排空 Web 请求。 */
public final class NacosLifecycle implements SmartLifecycle {
  private final NacosRuntime runtime;
  private final List<PlatformReadinessCheck> checks;

  public NacosLifecycle(NacosRuntime runtime, List<PlatformReadinessCheck> checks) {
    this.runtime = runtime;
    this.checks = List.copyOf(checks);
  }

  @Override
  public void start() {
    try {
      runtime.start(() -> checks.forEach(PlatformReadinessCheck::verify));
    } catch (Exception failure) {
      try {
        runtime.close();
      } catch (Exception ignored) {
        /* 保持未就绪。 */
      }
      throw new IllegalStateException("依赖检查或 Nacos 注册失败，应用不能就绪");
    }
  }

  @Override
  public void stop() {
    try {
      runtime.close();
    } catch (Exception failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Nacos 摘除未完全成功，请核对实例；已停止本地就绪状态");
    }
  }

  @Override
  public boolean isRunning() {
    return runtime.ready();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 500;
  }
}
