package com.example.sample;

import com.ken.platform.foundation.config.PlatformService;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.PlatformReadinessCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@PlatformService("sample-java-service")
public class SampleApplication {
  @Bean
  BusinessSettings businessSettings(
      com.ken.platform.foundation.config.RuntimeParameters parameters) {
    return BusinessSettings.from(parameters.business());
  }

  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }

  @Bean
  PlatformReadinessCheck peerDiscovery(NacosRuntime runtime, Environment environment) {
    return () -> {
      String peer = environment.getProperty("PEER_SERVICE");
      if (peer != null && !peer.isBlank()) {
        if (!peer.equals("sample-python-service"))
          throw new IllegalArgumentException("示例只允许固定协作服务");
        try {
          runtime.watch(peer);
        } catch (Exception failure) {
          throw new IllegalStateException("协作服务发现失败", failure);
        }
      }
    };
  }
}
