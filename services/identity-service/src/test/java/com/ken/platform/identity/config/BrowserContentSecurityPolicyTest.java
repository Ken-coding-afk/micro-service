package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.PrincipalType;
import com.ken.platform.identity.dal.dataobject.oauth.ClientPolicyDO.Status;
import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

class BrowserContentSecurityPolicyTest {
  private static final String CALLBACK = "https://client.example/callback?fixed=private-fixture";
  private final ClientStore store = mock(ClientStore.class);
  private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
  private final Identity identity =
      new Identity(
          UUID.randomUUID(), LoginRealm.TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, 1L, false);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private RegisteredClient client(String redirect, Status status) {
    var client =
        RegisteredClient.withId("registered-app")
            .clientId("app")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirect)
            .scope("profile")
            .build();
    when(store.byClientId("app"))
        .thenReturn(
            new ClientStore.Snapshot(
                client,
                new ClientPolicyDO(
                    PrincipalType.USER,
                    null,
                    Set.of(identity.tenant()),
                    Set.of("business"),
                    status,
                    1)));
    return client;
  }

  private MockHttpServletRequest saved(String redirect) {
    var original = new MockHttpServletRequest("GET", "/oauth2/authorize");
    original.setParameter("client_id", "app");
    original.setParameter("redirect_uri", redirect);
    new HttpSessionRequestCache().saveRequest(original, new MockHttpServletResponse());
    var request = new MockHttpServletRequest("GET", "/identity/login");
    request.setSession(original.getSession());
    return request;
  }

  private MockHttpServletRequest consent(RegisteredClient client, Identity owner) {
    var authentication = UsernamePasswordAuthenticationToken.authenticated(owner, null, Set.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://identity.example/oauth2/authorize")
            .clientId("app")
            .redirectUri(client.getRedirectUris().iterator().next())
            .scope("profile")
            .state("client-state")
            .build();
    var pending =
        OAuth2Authorization.withRegisteredClient(client)
            .principalName(owner.getName())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .attribute("state", "server-state")
            .attribute(Principal.class.getName(), authentication)
            .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest)
            .build();
    when(authorizations.findByToken("server-state", new OAuth2TokenType("state")))
        .thenReturn(pending);
    var request = new MockHttpServletRequest("GET", "/identity/consent");
    request.setParameter("client_id", "app");
    request.setParameter("state", "server-state");
    return request;
  }

  private String policy(String env, MockHttpServletRequest request) {
    var response = new MockHttpServletResponse();
    new BrowserContentSecurityPolicy(
            new MockEnvironment().withProperty("APP_ENV", env), store, authorizations)
        .writeHeaders(request, response);
    return response.getHeader("Content-Security-Policy");
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "test", "prod"})
  void savedRequestAllowsItsRegisteredHttpsCallbackWithoutQueryOrOtherClients(String env) {
    client(CALLBACK, Status.ACTIVE);
    assertThat(policy(env, saved(CALLBACK)))
        .endsWith("form-action 'self' https://client.example/callback")
        .doesNotContain("private-fixture", "unsafe-inline");
    verify(store, never()).localSwaggerRedirects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/identity/login", "/identity/password/initial"})
  void loginAndInitialPasswordPostsKeepTheSavedFlow(String path) {
    client(CALLBACK, Status.ACTIVE);
    var request = saved(CALLBACK);
    request.setRequestURI(path);
    request.setMethod("POST");
    assertThat(policy("prod", request)).endsWith(" https://client.example/callback");
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "test", "prod"})
  void consentUsesTheValidatedServerStateAndCurrentSession(String env) {
    var request = consent(client(CALLBACK, Status.ACTIVE), identity);
    request.setParameter("redirect_uri", "https://evil.example/callback");
    assertThat(policy(env, request))
        .endsWith(" https://client.example/callback")
        .doesNotContain("evil.example", "private-fixture");
  }

  @Test
  void sameUserInAnotherSessionCannotBorrowTheConsentTarget() {
    var request = consent(client(CALLBACK, Status.ACTIVE), identity);
    var other =
        new Identity(
            UUID.randomUUID(), identity.realm(), identity.tenant(), identity.user(), 1, 1L, false);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(other, null, Set.of()));
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
  }

  @Test
  void missingConsumedForgedAndDuplicatedStateCannotAddACallback() {
    var request = consent(client(CALLBACK, Status.ACTIVE), identity);
    request.setParameter("state", "unknown");
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    request.setParameter("state", "server-state", "unknown");
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    request.setParameter("state", "server-state");
    request.setParameter("client_id", "other");
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    request.setParameter("client_id", "app");
    when(authorizations.findByToken("server-state", new OAuth2TokenType("state"))).thenReturn(null);
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
  }

  @Test
  void bareLoginAndSessionPageDoNotEnumerateAnyRegisteredCallbacks() {
    var request = new MockHttpServletRequest("GET", "/identity/login");
    request.setParameter("client_id", "app");
    request.setParameter("redirect_uri", CALLBACK);
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    var session = saved(CALLBACK);
    session.setRequestURI("/identity/session");
    assertThat(policy("prod", session)).endsWith("form-action 'self'");
    verifyNoInteractions(store, authorizations);
  }

  @Test
  void disabledClientAndRemovedOrDifferentCallbackRemainSameOrigin() {
    var request = consent(client(CALLBACK, Status.ACTIVE), identity);
    client(CALLBACK, Status.DISABLED);
    assertThat(policy("prod", saved(CALLBACK))).endsWith("form-action 'self'");
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    client("https://other.example/callback", Status.ACTIVE);
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
    assertThat(policy("prod", saved(CALLBACK))).endsWith("form-action 'self'");
    when(store.byClientId("app")).thenReturn(null);
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
  }

  @Test
  void localHttpExceptionDoesNotEscapeToTestOrProduction() {
    String callback = "http://localhost:48083/docs/oauth2-redirect";
    client(callback, Status.ACTIVE);
    assertThat(policy("local", saved(callback))).endsWith(" " + callback);
    assertThat(policy("test", saved(callback))).endsWith("form-action 'self'");
    assertThat(policy("prod", saved(callback))).endsWith("form-action 'self'");
    String remote = "http://remote.example/callback";
    client(remote, Status.ACTIVE);
    assertThat(policy("local", saved(remote))).endsWith("form-action 'self'");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://client.example/callback#fragment",
        "https://user@client.example/callback",
        "https://client.example:0/callback",
        "https://client.example/*",
        "https://client.example:99999/callback"
      })
  void malformedSourcesCannotInjectAHeaderEvenIfPersisted(String callback) {
    // 官方构造器已拒绝 fragment；这里独立验证响应头对不可信持久化返回值的防线。
    var malformed = mock(RegisteredClient.class);
    when(malformed.getRedirectUris()).thenReturn(Set.of(callback));
    when(store.byClientId("app"))
        .thenReturn(
            new ClientStore.Snapshot(
                malformed,
                new ClientPolicyDO(
                    PrincipalType.USER,
                    null,
                    Set.of(identity.tenant()),
                    Set.of("business"),
                    Status.ACTIVE,
                    1)));
    assertThat(policy("prod", saved(callback))).endsWith("form-action 'self'");
  }

  @Test
  void pathSemicolonIsEncodedInsteadOfStartingAnotherDirective() {
    String callback = "https://client.example/callback;v1?private=fixture";
    client(callback, Status.ACTIVE);
    assertThat(policy("prod", saved(callback)))
        .endsWith(" https://client.example/callback%3Bv1")
        .doesNotContain("private=fixture");
  }

  @Test
  void unavailableDatabaseKeepsSameOriginPolicy() {
    var request = saved(CALLBACK);
    when(store.byClientId("app"))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
    assertThat(policy("prod", request)).endsWith("form-action 'self'");
  }
}
