package com.ken.platform.foundation.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.ken.platform.foundation.discovery.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    after = PlatformAutoConfiguration.class,
    afterName = {"com.alibaba.cloud.nacos.NacosServiceAutoConfiguration"})
@ConditionalOnClass({NacosConfigManager.class, NacosServiceManager.class})
@ConditionalOnBean(NacosSettings.class)
public class PlatformNacosAutoConfiguration {
  @Bean(destroyMethod = "")
  NacosRuntime platformNacosRuntime(
      NacosSettings settings,
      NacosServiceManager naming,
      NacosConfigManager config,
      ServiceDiscovery discovery) {
    var properties = settings.connectionProperties();
    properties.setProperty("namingLoadCacheAtStart", "false");
    return new NacosRuntime(
        settings, naming.getNamingService(properties), config.getConfigService(), discovery);
  }

  @Bean
  NacosLifecycle platformNacosLifecycle(
      NacosRuntime runtime, ObjectProvider<PlatformReadinessCheck> checks) {
    return new NacosLifecycle(runtime, checks.orderedStream().toList());
  }
}
