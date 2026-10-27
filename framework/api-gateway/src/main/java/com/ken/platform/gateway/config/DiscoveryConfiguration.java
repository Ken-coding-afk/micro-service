package com.ken.platform.gateway.config;

import com.ken.platform.foundation.config.PlatformLoadBalancerConfiguration;
import com.ken.platform.foundation.config.RuntimeParameters;
import io.netty.channel.ChannelOption;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = PlatformLoadBalancerConfiguration.class)
public class DiscoveryConfiguration {
  @Bean
  HttpClientCustomizer noAutomaticRequestReplay(RuntimeParameters parameters) {
    return client ->
        client
            .disableRetry(true)
            .followRedirect(false)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.max(1, (int) (parameters.connectTimeoutSeconds() * 1000)));
  }
}
