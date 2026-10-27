package com.ken.platform.identity.config;

import com.ken.platform.identity.service.oauth.SigningKeys;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Configuration(proxyBeanMethods = false)
public class IdentitySigningConfiguration {
  @Bean
  SigningKeys identitySigningKeys(Environment environment) {
    String reference = environment.getProperty("IDENTITY_SIGNING_JWK_SECRET_FILE", "");
    if (reference.isBlank())
      throw new IllegalArgumentException("缺少 IDENTITY_SIGNING_JWK_SECRET_FILE");
    try {
      return SigningKeys.load(Path.of(reference));
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("身份签名密钥文件缺失、不可读或格式不合法");
    }
  }

  @Bean
  JWKSource<SecurityContext> jwkSource(SigningKeys keys) {
    return keys.publicSource();
  }

  @Bean
  JwtEncoder jwtEncoder(SigningKeys keys) {
    return keys.encoder();
  }

  @Bean
  JwtDecoder jwtDecoder(JWKSource<SecurityContext> source) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(source);
  }
}
