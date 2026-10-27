package com.ken.platform.foundation.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.ken.platform.foundation.data.UuidTypeHandler;
import java.util.UUID;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    beforeName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@ConditionalOnClass(name = "com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer")
public class PlatformMybatisAutoConfiguration {
  @Bean
  ConfigurationCustomizer platformUuidMapping() {
    return configuration ->
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
  }
}
