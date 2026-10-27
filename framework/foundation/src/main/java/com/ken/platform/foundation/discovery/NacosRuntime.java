package com.ken.platform.foundation.discovery;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.ken.platform.foundation.config.NacosConfigReader;
import com.ken.platform.foundation.config.NacosSettings;
import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.http.PlatformException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 复用 SCA 提供的官方客户端；注册与查询在启动/后台线程，不阻塞 Gateway event loop。 */
public final class NacosRuntime implements AutoCloseable {
  private final NacosSettings settings;
  private final NamingService naming;
  private final ConfigService config;
  private final ServiceDiscovery discovery;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "platform-nacos");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, EventListener> subscriptions = new HashMap<>();
  private volatile RuntimeParameters parameters;
  private volatile boolean ready;
  private boolean registrationAttempted;
  private Listener configListener;

  public NacosRuntime(
      NacosSettings settings,
      NamingService naming,
      ConfigService config,
      ServiceDiscovery discovery) {
    this.settings = settings;
    this.naming = naming;
    this.config = config;
    this.discovery = discovery;
  }

  public void start(Runnable dependencyChecks) throws NacosException {
    parameters = loadParameters();
    applyLogging(parameters);
    dependencyChecks.run();
    configListener =
        new Listener() {
          public Executor getExecutor() {
            return executor;
          }

          public void receiveConfigInfo(String ignored) {
            try {
              RuntimeParameters candidate = loadParameters();
              if (parameters.sameRestartFields(candidate)) {
                parameters = candidate;
                applyLogging(candidate);
              } else {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("候选配置改变重启字段，拒绝在线应用");
              }
            } catch (NacosException | IllegalArgumentException invalid) {
              org.slf4j.LoggerFactory.getLogger(getClass()).warn("配置快照获取或校验失败，保留已验证配置");
            }
          }
        };
    config.addListener("application-shared.yaml", "DEFAULT_GROUP", configListener);
    config.addListener(settings.serviceName() + ".yaml", "DEFAULT_GROUP", configListener);
    if (settings.role().equals("api")) {
      Instance instance = new Instance();
      instance.setIp(settings.advertiseHost());
      instance.setPort(settings.advertisePort());
      instance.setEphemeral(true);
      instance.setMetadata(Map.of("role", "api", "scheme", settings.scheme()));
      registrationAttempted = true;
      naming.registerInstance(settings.serviceName(), "DEFAULT_GROUP", instance);
    }
    ready = true;
  }

  private RuntimeParameters loadParameters() throws NacosException {
    return RuntimeParameters.load(
        NacosConfigReader.read(config, "application-shared.yaml"),
        NacosConfigReader.read(config, settings.serviceName() + ".yaml"),
        settings.serviceName());
  }

  public synchronized void watch(String service) throws NacosException {
    if (subscriptions.containsKey(service)) {
      return;
    }
    refresh(service);
    EventListener listener = ignored -> executor.execute(() -> tryRefresh(service));
    naming.subscribe(service, "DEFAULT_GROUP", listener);
    subscriptions.put(service, listener);
    executor.scheduleWithFixedDelay(() -> tryRefresh(service), 15, 15, TimeUnit.SECONDS);
  }

  private void refresh(String service) throws NacosException {
    // subscribe=false 强制进行网络查询；不能将 SDK 旧缓存当成新确认。
    var instances =
        naming.selectInstances(service, "DEFAULT_GROUP", true, false).stream()
            .filter(
                instance ->
                    instance.isEnabled() && instance.isHealthy() && instance.getWeight() > 0)
            .map(
                instance ->
                    new ServiceInstance(
                        instance.getIp(),
                        instance.getPort(),
                        instance.getMetadata().getOrDefault("scheme", "http")))
            .toList();
    discovery.confirm(service, instances);
  }

  private void tryRefresh(String service) {
    try {
      refresh(service);
    } catch (NacosException | IllegalArgumentException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("服务目录网络确认失败，保留原确认时间");
    }
  }

  private static void applyLogging(RuntimeParameters parameters) {
    String level = parameters.logLevel().equals("WARNING") ? "WARN" : parameters.logLevel();
    org.springframework.boot.logging.LoggingSystem.get(NacosRuntime.class.getClassLoader())
        .setLogLevel(null, org.springframework.boot.logging.LogLevel.valueOf(level));
  }

  public boolean ready() {
    return ready;
  }

  public RuntimeParameters parameters() {
    if (parameters == null) {
      throw new PlatformException("DEPENDENCY_UNAVAILABLE", "配置尚未就绪", 503);
    }
    return parameters;
  }

  @Override
  public synchronized void close() throws NacosException {
    ready = false;
    NacosException failure = null;
    try {
      if (registrationAttempted) {
        naming.deregisterInstance(
            settings.serviceName(),
            "DEFAULT_GROUP",
            settings.advertiseHost(),
            settings.advertisePort());
        registrationAttempted = false;
      }
    } catch (NacosException unavailable) {
      failure = unavailable;
    }
    executor.shutdownNow();
    for (var entry : subscriptions.entrySet()) {
      try {
        naming.unsubscribe(entry.getKey(), "DEFAULT_GROUP", entry.getValue());
      } catch (NacosException unavailable) {
        if (failure == null) {
          failure = unavailable;
        }
      }
    }
    subscriptions.clear();
    if (configListener != null) {
      config.removeListener("application-shared.yaml", "DEFAULT_GROUP", configListener);
      config.removeListener(settings.serviceName() + ".yaml", "DEFAULT_GROUP", configListener);
    }
    // 客户端由 SCA/Spring 容器持有并关闭，不重复销毁共享客户端。
    if (failure != null) {
      throw failure;
    }
  }
}
