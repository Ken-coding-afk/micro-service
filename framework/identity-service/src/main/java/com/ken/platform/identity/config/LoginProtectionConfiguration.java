package com.ken.platform.identity.config;

import com.ken.platform.identity.service.login.LoginProtection;
import io.lettuce.core.ClientOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class LoginProtectionConfiguration {
  @Bean
  LettuceConnectionFactory identityRedisConnectionFactory(Environment environment)
      throws java.io.IOException {
    String appEnv = environment.getRequiredProperty("APP_ENV");
    if (!java.util.Set.of("local", "test", "prod").contains(appEnv))
      throw new IllegalArgumentException("未知 Redis 环境");
    String host = environment.getRequiredProperty("REDIS_HOST");
    int port = Integer.parseInt(environment.getRequiredProperty("REDIS_PORT"));
    Path secret = Path.of(environment.getRequiredProperty("REDIS_PASSWORD_SECRET_FILE"));
    if (host.isBlank()
        || host.contains("/")
        || host.contains("@")
        || port < 1
        || port > 65535
        || !secret.isAbsolute()) {
      throw new IllegalArgumentException("Redis 地址/秘密引用不合法");
    }
    var server = new RedisStandaloneConfiguration(host, port);
    String password = Files.readString(secret).strip();
    if (password.isEmpty()) throw new IllegalArgumentException("Redis 秘密文件不能为空");
    server.setPassword(RedisPassword.of(password));
    var client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofSeconds(1))
            .clientOptions(
                ClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
    if (!appEnv.equals("local")) client.useSsl();
    return new LettuceConnectionFactory(server, client.build());
  }

  @Bean
  StringRedisTemplate identityLoginRedis(LettuceConnectionFactory identityRedisConnectionFactory) {
    return new StringRedisTemplate(identityRedisConnectionFactory);
  }

  @Bean
  LoginProtection loginProtection(StringRedisTemplate identityLoginRedis, Environment environment) {
    return new LoginProtection(
        identityLoginRedis,
        environment.getRequiredProperty("PROJECT_CODE"),
        environment.getRequiredProperty("APP_ENV"));
  }
}
