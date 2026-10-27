package com.ken.platform.foundation.resilience;

import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.ken.platform.foundation.config.NacosConfigReader;
import com.ken.platform.foundation.config.NacosSettings;
import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.slf4j.LoggerFactory;

/** 规则监听由官方数据源负责；启动网络校验失败不得注册。 */
public final class SentinelRuleSource implements PlatformReadinessCheck, AutoCloseable {
  private final NacosSettings settings;
  private final ConfigService config;
  private NacosDataSource<List<FlowRule>> flow;
  private NacosDataSource<List<DegradeRule>> degrade;
  private volatile boolean flowLoaded;
  private volatile boolean degradeLoaded;

  public SentinelRuleSource(NacosSettings settings, ConfigService config) {
    this.settings = settings;
    this.config = config;
    com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry.getInstance()
        .addStateChangeObserver(
            "platform-metrics",
            (previous, current, rule, snapshot) ->
                io.micrometer.core.instrument.Metrics.counter(
                        "platform.circuit.transitions",
                        "resource",
                        rule.getResource(),
                        "state",
                        current.name())
                    .increment());
  }

  @Override
  public synchronized void verify() {
    if (flow != null && degrade != null) return;
    String flowId = settings.serviceName() + "-sentinel-flow.json";
    String degradeId = settings.serviceName() + "-sentinel-degrade.json";
    try {
      SentinelRules.flow(NacosConfigReader.read(config, flowId));
      SentinelRules.degrade(NacosConfigReader.read(config, degradeId));
      Properties properties = new RedactedProperties();
      properties.putAll(settings.connectionProperties());
      flow =
          new NacosDataSource<>(
              properties,
              "DEFAULT_GROUP",
              flowId,
              text -> {
                var rules = SentinelRules.flow(text);
                flowLoaded = true;
                applied(flowId, text);
                return rules;
              });
      degrade =
          new NacosDataSource<>(
              properties,
              "DEFAULT_GROUP",
              degradeId,
              text -> {
                var rules = SentinelRules.degrade(text);
                degradeLoaded = true;
                applied(degradeId, text);
                return rules;
              });
      // 官方构造器会吞掉异常；必须检查构造结果，不允许空规则冒充成功。
      if (!flowLoaded || !degradeLoaded) {
        throw new IllegalStateException("Sentinel 规则监听初始化失败");
      }
      FlowRuleManager.register2Property(flow.getProperty());
      DegradeRuleManager.register2Property(degrade.getProperty());
    } catch (Exception failure) {
      LoggerFactory.getLogger(SentinelRuleSource.class)
          .warn("Sentinel rule initialization rejected; service will not register");
      close();
      throw new IllegalStateException("Sentinel 规则初始化失败，服务不注册");
    }
  }

  private static void applied(String dataId, String text) {
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(text.getBytes(StandardCharsets.UTF_8)));
      LoggerFactory.getLogger(SentinelRuleSource.class)
          .info("Sentinel rule accepted dataId={} sha256={}", dataId, digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public synchronized void close() {
    if (flow != null) {
      try {
        flow.close();
      } catch (Exception ignored) {
      }
      flow = null;
    }
    if (degrade != null) {
      try {
        degrade.close();
      } catch (Exception ignored) {
      }
      degrade = null;
    }
    flowLoaded = false;
    degradeLoaded = false;
    com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry.getInstance()
        .removeStateChangeObserver("platform-metrics");
  }

  public static final class RedactedProperties extends Properties {
    @Override
    public synchronized String toString() {
      return "{Nacos connection properties redacted}";
    }
  }
}
