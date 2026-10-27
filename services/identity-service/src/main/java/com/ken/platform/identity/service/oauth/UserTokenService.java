package com.ken.platform.identity.service.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.authorization.AuthorizationRepository;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.dal.mapper.oauth.UserTokenStore;
import com.ken.platform.identity.service.authorization.AuthorizationEvaluator;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用户 token 的实时事实与唯一授权计算入口；成功结果不缓存。 */
@Service
public final class UserTokenService {
  public record ApplicationPermissions(
      Set<String> scopes,
      Set<String> permissions,
      @JsonProperty("data_scopes") Map<String, String> dataScopes) {
    public ApplicationPermissions {
      scopes = Set.copyOf(scopes);
      permissions = Set.copyOf(permissions);
      dataScopes = Map.copyOf(dataScopes);
    }
  }

  public record ApplicationView(
      String view,
      String sub,
      @JsonProperty("principal_type") String principalType,
      @JsonProperty("tenant_id") java.util.UUID tenantId,
      @JsonProperty("client_id") String clientId,
      Map<String, Object> profile,
      Map<String, ApplicationPermissions> audiences,
      @JsonProperty("as_of") Instant asOf) {
    public ApplicationView {
      profile = Map.copyOf(profile);
      audiences = Map.copyOf(audiences);
    }
  }

  public static final String APPLICATION_VIEW =
      UserTokenService.class.getName() + ".applicationView";
  public static final String AUTHENTICATED_AT =
      UserTokenService.class.getName() + ".authenticatedAt";

  /** 仅本地身份资源适配器使用；不进入 /me 或标准内省响应。 */
  public static final String OWN_IDENTITY = UserTokenService.class.getName() + ".ownIdentity";

  private record Live(
      OAuth2Authorization authorization,
      ClientStore.Snapshot client,
      Identity user,
      Set<String> scopes,
      Instant authenticatedAt) {}

  private final ClientStore clients;
  private final ProtocolAuthorizations authorizations;
  private final LoginStore sessions;
  private final PasswordLoginService logins;
  private final UserTokenStore users;
  private final AuthorizationRepository grants;
  private final AuthorizationEvaluator evaluator;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public UserTokenService(
      ClientStore clients,
      ProtocolAuthorizations authorizations,
      LoginStore sessions,
      PasswordLoginService logins,
      UserTokenStore users,
      AuthorizationRepository grants,
      AuthorizationEvaluator evaluator,
      PlatformTransactionManager manager,
      Clock clock) {
    this.clients = clients;
    this.authorizations = authorizations;
    this.sessions = sessions;
    this.logins = logins;
    this.users = users;
    this.grants = grants;
    this.evaluator = evaluator;
    this.clock = clock;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
  }

  public boolean isUserToken(String token) {
    var value = authorizations.observeByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    String type = value == null ? null : value.getAttribute(ProtocolAuthorizations.TYPE);
    return "user".equals(type) || "platform".equals(type);
  }

  public OAuth2TokenIntrospection introspect(String token, OAuth2ClientAuthenticationToken caller) {
    ownBoundary();
    return transactions.execute(
        status -> {
          sessions.lockCatalog();
          if (caller == null || !caller.isAuthenticated() || caller.getRegisteredClient() == null)
            throw new OAuth2AuthenticationException("invalid_client");
          var observed = authorizations.observeByToken(token, OAuth2TokenType.ACCESS_TOKEN);
          var ids = new TreeSet<String>();
          ids.add(caller.getRegisteredClient().getId());
          if (observed != null) ids.add(observed.getRegisteredClientId());
          var snapshots = new HashMap<String, ClientStore.Snapshot>();
          for (String id : ids) snapshots.put(id, clients.lockSnapshot(id));
          var reader = snapshots.get(caller.getRegisteredClient().getId());
          if (reader == null
              || reader.policy().status() != Status.ACTIVE
              || !Objects.equals(
                  reader.client().getClientSecret(), caller.getRegisteredClient().getClientSecret())
              || !reader
                  .client()
                  .getClientAuthenticationMethods()
                  .contains(caller.getClientAuthenticationMethod()))
            throw new OAuth2AuthenticationException("invalid_client");
          var scopes = reader.client().getScopes();
          if (reader.policy().principalType() != PrincipalType.SERVICE
              || reader.policy().ownerTenantId() != null
              || !reader.policy().allowedTenantIds().isEmpty()
              || !reader.policy().audiences().equals(Set.of("identity-service"))
              || scopes.size() != 1
              || !scopes.iterator().next().startsWith("internal.introspect:"))
            throw new OAuth2AuthenticationException("unauthorized_client");
          String audience = scopes.iterator().next().substring(20);
          if (!clients.audienceExists(audience))
            throw new OAuth2AuthenticationException("unauthorized_client");
          var live =
              observed == null
                  ? null
                  : live(
                      token, observed, snapshots.get(observed.getRegisteredClientId()), audience);
          if (live == null) return OAuth2TokenIntrospection.builder(false).build();
          boolean platform = live.user().realm() == LoginRealm.PLATFORM;
          var result = evaluate(live, audience);
          Set<String> allowedScopes = audienceScopes(live, audience);
          var access = live.authorization().getAccessToken().getToken();
          var response =
              OAuth2TokenIntrospection.builder(true)
                  .subject(live.user().user().toString())
                  .clientId(live.client().client().getClientId())
                  .audience(audience)
                  .issuer(
                      org.springframework.security.oauth2.server.authorization.context
                          .AuthorizationServerContextHolder.getContext()
                          .getIssuer())
                  .tokenType(access.getTokenType().getValue())
                  .issuedAt(access.getIssuedAt())
                  .expiresAt(access.getExpiresAt())
                  .scopes(values -> values.addAll(allowedScopes.stream().sorted().toList()))
                  .claim("principal_type", platform ? "platform" : "user")
                  .claim("permissions", result.permissions().stream().sorted().toList())
                  .claim(
                      "data_scopes",
                      result.dataScopes().entrySet().stream()
                          .collect(
                              java.util.stream.Collectors.toMap(
                                  Map.Entry::getKey, entry -> entry.getValue().name())));
          if (!platform) response.claim("tenant_id", live.user().tenant().toString());
          return response.build();
        });
  }

  /** /me 直接使用本地 Authorization 的真实应用上下文，不能由浏览器选择 client/audience。 */
  public OAuth2AuthenticatedPrincipal applicationPrincipal(String token) {
    ownBoundary();
    try {
      OAuth2AuthenticatedPrincipal result =
          transactions.execute(
              status -> {
                sessions.lockCatalog();
                var observed = authorizations.observeByToken(token, OAuth2TokenType.ACCESS_TOKEN);
                var current =
                    observed == null
                        ? null
                        : clients.lockSnapshot(observed.getRegisteredClientId());
                var live = observed == null ? null : live(token, observed, current, null);
                if (live == null) return null;
                var audiences = new TreeSet<>(live.client().policy().audiences());
                audiences.retainAll(
                    strings(live.authorization().getAttribute(ProtocolAuthorizations.AUDIENCES)));
                if (audiences.isEmpty()) return null;
                Map<String, ApplicationPermissions> applications = new java.util.TreeMap<>();
                for (String audience : audiences) {
                  var evaluated = evaluate(live, audience);
                  applications.put(
                      audience,
                      new ApplicationPermissions(
                          audienceScopes(live, audience),
                          evaluated.permissions(),
                          evaluated.dataScopes().entrySet().stream()
                              .collect(
                                  java.util.stream.Collectors.toMap(
                                      Map.Entry::getKey, entry -> entry.getValue().name()))));
                }
                var view =
                    new ApplicationView(
                        "APPLICATION_EFFECTIVE",
                        live.user().user().toString(),
                        live.user().realm() == LoginRealm.PLATFORM ? "platform" : "user",
                        live.user().tenant(),
                        live.client().client().getClientId(),
                        profileClaims(live.user(), live.scopes()),
                        applications,
                        clock.instant());
                var access = live.authorization().getAccessToken().getToken();
                var identity = applications.get("identity-service");
                Set<String> localScopes = identity == null ? Set.of() : identity.scopes();
                Set<String> localPermissions = identity == null ? Set.of() : identity.permissions();
                Map<String, DataScope> localRanges = new java.util.TreeMap<>();
                if (identity != null)
                  identity
                      .dataScopes()
                      .forEach((code, scope) -> localRanges.put(code, DataScope.valueOf(scope)));
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("sub", view.sub());
                attributes.put("principal_type", view.principalType());
                attributes.put("client_id", view.clientId());
                attributes.put("iat", access.getIssuedAt());
                attributes.put("exp", access.getExpiresAt());
                attributes.put("aud", audiences.stream().toList());
                attributes.put("scope", localScopes.stream().sorted().toList());
                attributes.put("permissions", localPermissions.stream().sorted().toList());
                attributes.put("data_scopes", identity == null ? Map.of() : identity.dataScopes());
                attributes.put(APPLICATION_VIEW, view);
                attributes.put(AUTHENTICATED_AT, live.authenticatedAt());
                if ((live.user().realm() == LoginRealm.PLATFORM
                        && live.client().client().getClientId().equals("identity-admin-platform"))
                    || (live.user().realm() == LoginRealm.TENANT
                        && live.client().client().getClientId().equals("identity-admin-tenant")))
                  attributes.put(OWN_IDENTITY, live.user());
                if (view.tenantId() != null)
                  attributes.put("tenant_id", view.tenantId().toString());
                return new CurrentPrincipal(
                    view.sub(),
                    view.principalType(),
                    view.tenantId(),
                    view.clientId(),
                    localScopes,
                    localPermissions,
                    localRanges,
                    Set.of(),
                    attributes);
              });
      if (result == null) throw new BadOpaqueTokenException("身份 access token 无效");
      return result;
    } catch (BadOpaqueTokenException invalid) {
      throw invalid;
    } catch (RuntimeException unavailable) {
      throw new AuthenticationServiceException("身份认证依赖当前不可用");
    }
  }

  private AuthorizationEvaluator.Evaluation evaluate(Live live, String audience) {
    boolean platform = live.user().realm() == LoginRealm.PLATFORM;
    var catalog = grants.permissions(platform);
    var roleGrants =
        platform
            // 固定受保护平台身份作为逻辑来源，不创建跨租户角色或返回伪造角色记录。
            ? catalog.stream()
                .map(
                    permission ->
                        new AuthorizationEvaluator.RoleGrant(
                            live.user().user(),
                            permission.code(),
                            null,
                            true,
                            live.user().authVersion()))
                .toList()
            : users.scoped(
                live.user().tenant(),
                () -> grants.grants(live.user().tenant(), live.user().user()));
    var capabilities =
        platform
            ? Set.of("APPLICATION_MANAGEMENT", "PROVIDER_MANAGEMENT", "SECURITY_POLICY_MANAGEMENT")
            : users.capabilities(live.user().tenant());
    return evaluator.evaluate(
        catalog,
        roleGrants,
        new AuthorizationEvaluator.ApplicationBoundary(
            live.client().client().getScopes(),
            live.scopes(),
            live.client().policy().audiences(),
            Set.copyOf(
                strings(live.authorization().getAttribute(ProtocolAuthorizations.AUDIENCES))),
            audience,
            users.serviceAudience(audience),
            capabilities));
  }

  private Set<String> audienceScopes(Live live, String audience) {
    var scopes =
        new HashSet<>(
            clients.businessScopes(live.client().policy().principalType(), Set.of(audience)));
    if (audience.equals("identity-service")) scopes.addAll(Set.of("openid", "profile", "email"));
    scopes.retainAll(live.scopes());
    return Set.copyOf(scopes);
  }

  private Live live(
      String token, OAuth2Authorization observed, ClientStore.Snapshot client, String audience) {
    if (client == null
        || client.policy().status() != Status.ACTIVE
        || client.policy().principalType() == PrincipalType.SERVICE
        || observed.getAttribute(ProtocolAuthorizations.REVOKED) != null
        || clients.audienceCount(client.policy().audiences()) != client.policy().audiences().size())
      return null;
    Authentication principal = observed.getAttribute(Principal.class.getName());
    if (principal == null
        || !principal.isAuthenticated()
        || !(principal.getPrincipal() instanceof Identity user)
        || !user.user().toString().equals(observed.getPrincipalName())
        || !user.sessionId()
            .toString()
            .equals(observed.getAttribute(ProtocolAuthorizations.SESSION))
        || !client
            .policy()
            .principalType()
            .name()
            .toLowerCase(java.util.Locale.ROOT)
            .equals(observed.getAttribute(ProtocolAuthorizations.TYPE))
        || user.realm() == LoginRealm.PLATFORM
            && client.policy().principalType() != PrincipalType.PLATFORM
        || user.realm() == LoginRealm.TENANT
            && (client.policy().principalType() != PrincipalType.USER
                || !client.policy().allowedTenantIds().contains(user.tenant()))
        || !strings(observed.getAttribute(ProtocolAuthorizations.TENANTS))
            .equals(user.tenant() == null ? List.of() : List.of(user.tenant().toString()))
        || audience != null
            && (!client.policy().audiences().contains(audience)
                || !strings(observed.getAttribute(ProtocolAuthorizations.AUDIENCES))
                    .contains(audience))) return null;
    if (logins.checkProtocolSession(user) == null) return null;
    var locked = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    if (locked == null
        || locked.getAccessToken() == null
        || !locked.getAccessToken().isActive()
        || locked.getAttribute(ProtocolAuthorizations.REVOKED) != null) return null;
    var scopes = new HashSet<>(locked.getAccessToken().getToken().getScopes());
    scopes.retainAll(client.client().getScopes());
    var session = sessions.session(user.sessionId(), false);
    if (session == null) return null;
    return new Live(locked, client, user, Set.copyOf(scopes), session.createdAt());
  }

  private Live userInfo(String token) {
    sessions.lockCatalog();
    var observed = authorizations.observeByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    var current = observed == null ? null : clients.lockSnapshot(observed.getRegisteredClientId());
    var live = observed == null ? null : live(token, observed, current, null);
    return live != null
            && live.scopes().contains("openid")
            && live.authorization().getToken(OidcIdToken.class) != null
        ? live
        : null;
  }

  public OAuth2AuthenticatedPrincipal userInfoPrincipal(String token) {
    ownBoundary();
    try {
      var live = transactions.execute(status -> userInfo(token));
      if (live == null) throw new BadOpaqueTokenException("身份 access token 无效");
      var access = live.authorization().getAccessToken().getToken();
      return new DefaultOAuth2AuthenticatedPrincipal(
          live.user().user().toString(),
          Map.of(
              "sub",
              live.user().user().toString(),
              "iat",
              access.getIssuedAt(),
              "exp",
              access.getExpiresAt(),
              "scope",
              live.scopes().stream().sorted().toList()),
          live.scopes().stream()
              .<org.springframework.security.core.GrantedAuthority>map(
                  scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
              .toList());
    } catch (BadOpaqueTokenException invalid) {
      throw invalid;
    } catch (RuntimeException unavailable) {
      throw new AuthenticationServiceException("身份认证依赖当前不可用");
    }
  }

  public Map<String, Object> userInfoClaims(String token) {
    ownBoundary();
    try {
      Map<String, Object> claims =
          transactions.execute(
              status -> {
                var live = userInfo(token);
                return live == null ? null : profileClaims(live.user(), live.scopes());
              });
      if (claims == null) throw new OAuth2AuthenticationException("invalid_token");
      return claims;
    } catch (OAuth2AuthenticationException known) {
      throw known;
    } catch (RuntimeException unavailable) {
      throw new OAuth2AuthenticationException("temporarily_unavailable");
    }
  }

  public Map<String, Object> profileClaims(Identity user, Set<String> scopes) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("资料投影必须在认证事务内");
    var profile = users.profile(user);
    if (profile == null) throw new OAuth2AuthenticationException("invalid_token");
    Map<String, Object> result = new HashMap<>();
    result.put("sub", user.user().toString());
    if (scopes.contains("profile")) {
      result.put("name", profile.name());
      result.put("preferred_username", profile.username());
    }
    if (scopes.contains("email") && profile.email() != null) {
      result.put("email", profile.email());
      result.put("email_verified", true);
    }
    return result;
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> values && values.stream().allMatch(String.class::isInstance)
        ? values.stream().map(String.class::cast).toList()
        : List.of();
  }

  private static void ownBoundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("内省入口必须拥有事务边界");
  }
}
