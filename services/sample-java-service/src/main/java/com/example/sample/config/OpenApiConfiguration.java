package com.example.sample.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 文档只在 local 开启；业务接口仍由 SDK 校验真实 token/租户/权限。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "APP_ENV", havingValue = "local")
public class OpenApiConfiguration {
  @Bean
  OpenAPI serviceOpenApi(Environment environment) {
    String origin = environment.getRequiredProperty("PUBLIC_ORIGIN");
    String tenant = environment.getProperty("SWAGGER_TENANT_CODE", "");
    String authorization = origin + "/oauth2/authorize";
    if (!tenant.isBlank()) {
      if (!tenant.matches("[a-z][a-z0-9-]{0,63}"))
        throw new IllegalArgumentException("Swagger 租户编码不合法");
      authorization += "?tenant_code=" + tenant;
    }
    var flow =
        new OAuthFlow()
            .authorizationUrl(authorization)
            .tokenUrl(origin + "/oauth2/token")
            .scopes(
                new Scopes()
                    .addString("profile", "基本身份")
                    .addString("java.items.read", "读取条目")
                    .addString("java.items.write", "写入条目"));
    return new OpenAPI()
        .info(new Info().title("sample-java-service").version("0.1.0"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "oauth2",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows().authorizationCode(flow))))
        .addSecurityItem(new SecurityRequirement().addList("oauth2"));
  }
}
