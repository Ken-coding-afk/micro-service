package com.ken.platform.identity.service.oauth;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** 明确字段的客户端登记合同；空租户集合不代表所有租户。 */
public final class ClientPolicyRules {
  public record Definition(
      String clientId,
      String name,
      PrincipalType principalType,
      UUID ownerTenantId,
      Set<UUID> allowedTenantIds,
      Set<String> audiences,
      Set<String> scopes,
      Set<String> redirectUris,
      Set<String> logoutUris,
      boolean publicClient,
      boolean firstParty,
      Status status) {}

  private final boolean local;

  public ClientPolicyRules(String environment) {
    if (!Set.of("local", "test", "prod").contains(environment == null ? "" : environment)) {
      throw new IllegalArgumentException("缺少受验证的客户端环境绑定");
    }
    local = environment.equals("local");
  }

  public Definition validate(Definition value) {
    if (value == null
        || value.clientId() == null
        || !value.clientId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
        || value.principalType() == null
        || value.status() == null
        || value.allowedTenantIds() == null
        || value.audiences() == null
        || value.scopes() == null
        || value.redirectUris() == null
        || value.logoutUris() == null
        || value.allowedTenantIds().stream().anyMatch(java.util.Objects::isNull)) {
      throw invalid("客户端标识、类型和集合字段不合法");
    }
    String name = value.name();
    if (name == null
        || name.isBlank()
        || name.codePointCount(0, name.length()) > 200
        || name.contains("<")
        || name.contains(">")
        || name.codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code >= 0xD800 && code <= 0xDFFF)) {
      throw invalid("客户端名称必须是最多 200 码点的纯文本");
    }
    if (value.ownerTenantId() != null
        && !value.allowedTenantIds().equals(Set.of(value.ownerTenantId()))) {
      throw invalid("租户拥有的客户端只能绑定本租户");
    }
    if (value.principalType() == PrincipalType.PLATFORM
        && (value.ownerTenantId() != null || !value.allowedTenantIds().isEmpty())) {
      throw invalid("平台客户端不能绑定租户身份");
    }
    if (value.audiences().isEmpty()) {
      throw invalid("必须登记明确的 audience");
    }
    for (String audience : value.audiences()) {
      if (audience == null || !audience.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) {
        throw invalid("audience 必须是已登记的明确标识");
      }
    }
    if (value.scopes().isEmpty()
        || value.scopes().stream()
            .anyMatch(
                scope -> scope == null || !scope.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}"))
        || String.join(",", value.scopes()).length() > 1000) {
      throw invalid("scope 集合不合法或超过协议表上限");
    }
    if (value.scopes().stream().anyMatch(scope -> scope.startsWith("internal.introspect:"))
        && value.scopes().size() != 1) {
      throw invalid("内省身份只能绑定一个固定资源 audience，不能兼任其他 scope");
    }
    if (value.principalType() == PrincipalType.SERVICE) {
      if (value.publicClient()
          || !value.redirectUris().isEmpty()
          || !value.logoutUris().isEmpty()
          || value.scopes().contains("openid")) {
        throw invalid("机器客户端必须使用机密身份且不包含浏览器协议");
      }
    } else {
      if (value.redirectUris().isEmpty() || value.publicClient() && value.firstParty()) {
        throw invalid("用户客户端需要准确回调，公开客户端必须征求同意");
      }
      value.redirectUris().forEach(this::callback);
      value.logoutUris().forEach(this::callback);
    }
    if (String.join(",", value.redirectUris()).length() > 1000
        || String.join(",", value.logoutUris()).length() > 1000) {
      throw invalid("回调集合超过协议表上限");
    }
    return new Definition(
        value.clientId(),
        name,
        value.principalType(),
        value.ownerTenantId(),
        Set.copyOf(value.allowedTenantIds()),
        Set.copyOf(value.audiences()),
        Set.copyOf(value.scopes()),
        Set.copyOf(value.redirectUris()),
        Set.copyOf(value.logoutUris()),
        value.publicClient(),
        value.firstParty(),
        value.status());
  }

  private void callback(String value) {
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      boolean loopback =
          Set.of("localhost", "127.0.0.1", "[::1]", "::1")
              .contains(host == null ? "" : host.toLowerCase(java.util.Locale.ROOT));
      if (host == null
          || uri.getRawFragment() != null
          || uri.getRawUserInfo() != null
          || value.contains("*")
          || value.contains(",")
          || !uri.toASCIIString().equals(value)
          || uri.getPort() > 65535
          || !("https".equals(uri.getScheme())
              || local && loopback && "http".equals(uri.getScheme()))) {
        throw invalid("回调必须是准确 HTTPS URI；local 仅允许 loopback HTTP");
      }
    } catch (PlatformException known) {
      throw known;
    } catch (RuntimeException malformed) {
      throw invalid("回调 URI 不合法");
    }
  }

  public void validateReferences(Definition value, ClientStore store) {
    if (store.tenantCount(value.allowedTenantIds()) != value.allowedTenantIds().size()
        || store.audienceCount(value.audiences()) != value.audiences().size()) {
      throw new PlatformException("CLIENT_REFERENCE_INVALID", "租户或 audience 尚未登记", 400);
    }
    var business = store.businessScopes(value.principalType(), value.audiences());
    for (String scope : value.scopes()) {
      boolean browser =
          value.principalType() != PrincipalType.SERVICE
              && Set.of("openid", "profile", "email").contains(scope);
      boolean publisher =
          scope.startsWith("catalog.publish:")
              && scope.substring(16).matches("[a-z][a-z0-9-]{0,127}")
              && !scope.substring(16).equals("identity-service");
      boolean introspection =
          scope.startsWith("internal.introspect:") && store.audienceExists(scope.substring(20));
      boolean technical =
          (publisher || introspection)
              && value.principalType() == PrincipalType.SERVICE
              && value.ownerTenantId() == null
              && value.allowedTenantIds().isEmpty()
              && value.audiences().equals(Set.of("identity-service"));
      boolean scopedBusiness =
          business.contains(scope)
              && (value.principalType() != PrincipalType.SERVICE
                  || !value.allowedTenantIds().isEmpty());
      if (!(browser || technical || scopedBusiness)) {
        throw new PlatformException(
            "CLIENT_SCOPE_NOT_REGISTERED", "scope 未登记、与 audience 不匹配或超出机器身份边界", 400);
      }
    }
  }

  public static PlatformException invalid(String message) {
    return new PlatformException("CLIENT_DEFINITION_INVALID", message, 400);
  }
}
