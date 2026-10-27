package com.ken.platform.foundation.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.nacos.api.config.ConfigService;
import java.util.Properties;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** refresh 之前读取完整配置；任何业务连接池/单例创建之前拒绝错误快照。 */
public final class PlatformContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  @Override
  public void initialize(ConfigurableApplicationContext context) {
    if (!PlatformEnvironmentPostProcessor.enabled()) {
      return;
    }
    NacosSettings settings =
        NacosSettings.from(PlatformEnvironmentPostProcessor.bindings(context.getEnvironment()));
    Properties credentials = settings.connectionProperties();
    var properties =
        new NacosConfigProperties() {
          @Override
          public Properties assembleConfigServiceProperties() {
            var result = new Properties();
            result.putAll(credentials);
            result.setProperty("enableRemoteSyncConfig", "true");
            result.setProperty("configLongPollTimeout", "5000");
            return result;
          }

          @Override
          public String toString() {
            return "NacosConfigProperties[protected]";
          }
        };
    properties.setServerAddr(settings.serverAddress());
    properties.setNamespace(settings.namespace());
    ConfigService config = null;
    try {
      var manager = new NacosConfigManager(properties);
      config = manager.getConfigService();
      RuntimeParameters parameters =
          RuntimeParameters.load(
              NacosConfigReader.read(config, "application-shared.yaml"),
              NacosConfigReader.read(config, settings.serviceName() + ".yaml"),
              settings.serviceName());
      if (parameters.sentinelEnabled()
          && !org.springframework.util.ClassUtils.isPresent(
              "com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource",
              context.getClassLoader())) {
        throw new IllegalStateException("启用 Sentinel 必须在服务中引入官方 core 与 datasource-nacos 依赖");
      }
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "platform-native-components",
                  NativeComponentSettings.properties(parameters.spring())));
      var factory = (DefaultListableBeanFactory) context.getBeanFactory();
      factory.registerSingleton("platformSettings", settings);
      factory.registerSingleton("platformParameters", parameters);
      factory.registerSingleton("nacosConfigManager", manager);
      ConfigService owned = config;
      factory.registerDisposableBean("nacosConfigManager", owned::shutDown);
    } catch (Exception failure) {
      if (config != null) {
        try {
          config.shutDown();
        } catch (Exception ignored) {
          /* 首次失败仍不能进入 ready。 */
        }
      }
      throw new IllegalStateException("Nacos 首次完整配置读取或校验失败，应用不能就绪");
    }
  }
}
