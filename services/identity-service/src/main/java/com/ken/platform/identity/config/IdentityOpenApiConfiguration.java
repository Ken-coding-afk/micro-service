package com.ken.platform.identity.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class IdentityOpenApiConfiguration {
  @Bean
  OpenAPI identityOpenApi(Environment environment) {
    String origin = environment.getRequiredProperty("PUBLIC_ORIGIN");
    var scopes =
        new Scopes()
            .addString("openid", "确认登录身份")
            .addString("platform.tenants", "平台租户管理")
            .addString("platform.identity", "平台指定租户身份管理")
            .addString("platform.permissions", "平台业务权限目录")
            .addString("platform.audit", "平台审计")
            .addString("platform.monitoring", "服务监控")
            .addString("platform.admins", "平台管理员")
            .addString("platform.clients", "应用与服务登记")
            .addString("platform.security", "平台安全策略")
            .addString("identity.users", "本租户用户管理")
            .addString("identity.groups", "本租户组织与成员管理")
            .addString("identity.roles", "本租户角色授权")
            .addString("identity.permissions", "本租户权限查询")
            .addString("identity.sessions", "本租户会话治理")
            .addString("identity.audit", "本租户审计");
    return new OpenAPI()
        .info(
            new Info()
                .title("项目身份与组织管理 API")
                .version("v1")
                .description(
                    "独立登记的应用使用 Authorization Code + S256 PKCE 和 Bearer token。平台接口要求有效 PLATFORM 用户及对应 scope；租户入口只管理认证租户，管理操作另需受保护租户管理员资格。分页page从0开始，size最大100。写入携带当前版本，409须刷新核对；创建通过X-Request-Id幂等。400输入错误、401凭据失效、403权限不足或需近期重新认证、404对象不可见、409冲突。所有管理响应包含code/message/data/request_id，初始密码及重置凭据仅一次返回。"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "identityOAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(
                            new OAuthFlows()
                                .authorizationCode(
                                    new OAuthFlow()
                                        .authorizationUrl(origin + "/oauth2/authorize")
                                        .tokenUrl(origin + "/oauth2/token")
                                        .scopes(scopes)))))
        .addSecurityItem(new SecurityRequirement().addList("identityOAuth"));
  }

  @Bean
  GroupedOpenApi managementOpenApi() {
    return GroupedOpenApi.builder()
        .group("identity-management")
        .pathsToMatch("/api/v1/identity/**")
        .pathsToExclude("/api/v1/identity/admin-config")
        .addOpenApiCustomizer(
            api ->
                api.getPaths()
                    .forEach(
                        (path, item) -> {
                          item.readOperationsMap()
                              .forEach(
                                  (method, operation) -> {
                                    List<String> required = managementScopes(path, method);
                                    if (required.isEmpty()) return;
                                    operation.setSecurity(
                                        List.of(
                                            new SecurityRequirement()
                                                .addList("identityOAuth", required)));
                                    operation
                                        .getResponses()
                                        .addApiResponse(
                                            "400",
                                            new ApiResponse()
                                                .description("INVALID_ARGUMENT：字段、类型或版本不合法"));
                                    operation
                                        .getResponses()
                                        .addApiResponse(
                                            "401",
                                            new ApiResponse()
                                                .description("INVALID_CREDENTIALS：缺少或无效访问令牌"));
                                    operation
                                        .getResponses()
                                        .addApiResponse(
                                            "403",
                                            new ApiResponse()
                                                .description(
                                                    "FORBIDDEN / RECENT_AUTH_REQUIRED：权限或近期认证不足"));
                                    operation
                                        .getResponses()
                                        .addApiResponse(
                                            "404", new ApiResponse().description("目标不存在或不可见"));
                                    operation
                                        .getResponses()
                                        .addApiResponse(
                                            "409",
                                            new ApiResponse()
                                                .description("版本过期、非空删除、组织环路或重复编码；重新读取后核对修改"));
                                  });
                        }))
        .build();
  }

  private static List<String> managementScopes(String path, HttpMethod method) {
    if (path.contains("/platform/tenants/{managementTenant}/identity")) {
      return path.contains("/audits")
          ? List.of("platform.identity", "platform.audit")
          : List.of("platform.identity");
    }
    if (path.endsWith("/platform/service-audiences"))
      return List.of(method == HttpMethod.GET ? "platform.permissions" : "platform.clients");
    if (path.contains("/platform/permissions") && path.endsWith("/roles"))
      return List.of("platform.permissions", "platform.identity");
    if (path.contains("/platform/permissions")) return List.of("platform.permissions");
    if (path.contains("/platform/tenants")) return List.of("platform.tenants");
    if (path.contains("/platform/audits")) return List.of("platform.audit");
    if (path.contains("/platform/admins")) return List.of("platform.admins");
    if (path.contains("/platform/oauth-clients")) return List.of("platform.clients");
    if (path.endsWith("/platform/security-policy")) return List.of("platform.security");
    if (!path.contains("/platform/")) {
      if (path.contains("/me/")) return List.of();
      if (path.contains("/users/") && path.endsWith("/groups"))
        return List.of("identity.groups", "identity.users");
      if (method == HttpMethod.GET && path.contains("/groups")) {
        if (path.endsWith("/members")) return List.of("identity.groups", "identity.users");
        if (path.contains("/roles/") || path.endsWith("/roles"))
          return List.of("identity.groups", "identity.roles");
      }
      if (path.contains("/groups")) return List.of("identity.groups");
      if (path.contains("/users/") && path.endsWith("/roles"))
        return List.of(method == HttpMethod.GET ? "identity.users" : "identity.roles");
      if (path.contains("/roles/") && path.endsWith("/members"))
        return List.of("identity.roles", "identity.users");
      if (path.contains("/roles") || path.contains("/role-permissions/"))
        return List.of("identity.roles");
      if (path.endsWith("/effective-permissions")) return List.of("identity.permissions");
      if (path.contains("/sessions") || path.endsWith(":sign-out-all"))
        return List.of("identity.sessions");
      if (path.contains("/users")) return List.of("identity.users");
      if (path.contains("/permissions")) return List.of("identity.permissions");
      if (path.endsWith("/audits")) return List.of("identity.audit");
    }
    return List.of();
  }
}
