package com.ken.platform.identity.config;

import com.ken.platform.identity.service.oauth.ClientPolicyRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ClientManagementConfiguration {
  @Bean
  ClientPolicyRules clientPolicyRules(Environment environment) {
    return new ClientPolicyRules(environment.getRequiredProperty("APP_ENV"));
  }
}
