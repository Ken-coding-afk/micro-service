package com.ken.platform.gateway.traffic;

import io.lettuce.core.ClientOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class GatewayRedisConfiguration {
  @Bean(destroyMethod = "destroy")
  LettuceConnectionFactory bffRedisConnectionFactory(Environment environment) throws Exception {
    String host = environment.getRequiredProperty("REDIS_HOST");
    int port = Integer.parseInt(environment.getRequiredProperty("REDIS_PORT"));
    Path file = Path.of(environment.getRequiredProperty("REDIS_PASSWORD_SECRET_FILE"));
    if (!host.matches("[A-Za-z0-9.:-]{1,253}")
        || port < 1
        || port > 65535
        || !file.isAbsolute()
        || !Files.isRegularFile(file)
        || Files.size(file) > 4096) throw new IllegalArgumentException("Gateway Redis 绑定不合法");
    var config = new RedisStandaloneConfiguration(host, port);
    String password = Files.readString(file).strip();
    if (password.isEmpty()) throw new IllegalArgumentException("Gateway Redis 秘密不能为空");
    config.setPassword(RedisPassword.of(password));
    var client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofSeconds(1))
            .clientOptions(
                ClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
    if (!environment.getRequiredProperty("APP_ENV").equals("local")) client.useSsl();
    return new LettuceConnectionFactory(config, client.build());
  }

  @Bean
  ReactiveStringRedisTemplate bffOperationRedis(
      @Qualifier("bffRedisConnectionFactory") LettuceConnectionFactory factory) {
    return new ReactiveStringRedisTemplate(factory);
  }
}
