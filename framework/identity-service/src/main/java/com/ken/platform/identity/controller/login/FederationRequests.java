package com.ken.platform.identity.controller.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** state/nonce/PKCE 使用 Spring；tenant 与绑定对象只来自服务器一次性意图。 */
public final class FederationRequests
    implements OAuth2AuthorizationRequestResolver,
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  public static final String INTENT = "identity.external.intent",
      COMPLETED = "identity.external.completed";
  private final DefaultOAuth2AuthorizationRequestResolver resolver;
  private final HttpSessionOAuth2AuthorizationRequestRepository repository =
      new HttpSessionOAuth2AuthorizationRequestRepository();
  private final Clock clock;

  public FederationRequests(ClientRegistrationRepository clients, Clock clock) {
    resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    this.clock = clock;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return decorate(request, resolver.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String id) {
    return decorate(request, resolver.resolve(request, id));
  }

  private OAuth2AuthorizationRequest decorate(
      HttpServletRequest request, OAuth2AuthorizationRequest original) {
    if (original == null) return null;
    var session = request.getSession(false);
    Object value = session == null ? null : session.getAttribute(INTENT);
    if (session != null) session.removeAttribute(INTENT);
    if (!(value instanceof Map<?, ?> intent)
        || !original.getAttribute("registration_id").equals(intent.get("provider"))
        || !valid(intent))
      throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
          "invalid_request");
    return OAuth2AuthorizationRequest.from(original)
        .attributes(attributes -> attributes.put(INTENT, intent))
        .build();
  }

  private boolean valid(Map<?, ?> intent) {
    try {
      return clock.millis() >= Long.parseLong((String) intent.get("created"))
          && clock.millis() - Long.parseLong((String) intent.get("created")) < 300000;
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    return repository.loadAuthorizationRequest(request);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest value, HttpServletRequest request, HttpServletResponse response) {
    repository.saveAuthorizationRequest(value, request, response);
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    var value = repository.removeAuthorizationRequest(request, response);
    if (value != null && value.getAttribute(INTENT) instanceof Map<?, ?> intent && valid(intent))
      request.setAttribute(COMPLETED, intent);
    else value = null;
    return value;
  }
}
