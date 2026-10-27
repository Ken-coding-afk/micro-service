package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.login.PasswordLoginService.Identity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class IdentitySessionSerializationTest {
  @Test
  void identitySecurityContextAndCsrfRoundTripWithExactTypes() {
    var identity =
        new Identity(
            UUID.randomUUID(),
            LoginRealm.TENANT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            2L,
            true);
    var security =
        new SecurityContextImpl(
            UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
    var serializer = IdentitySessionConfiguration.serializer();
    var copy = (SecurityContextImpl) serializer.deserialize(serializer.serialize(security));
    assertThat(copy.getAuthentication().getPrincipal()).isEqualTo(identity);
    assertThat(copy.getAuthentication().getCredentials()).isNull();
    assertThat(copy.getAuthentication().getName()).isEqualTo(identity.user().toString());
    var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "fixture-value");
    assertThat(((DefaultCsrfToken) serializer.deserialize(serializer.serialize(csrf))).getToken())
        .isEqualTo("fixture-value");
  }

  @Test
  void sessionMetadataNumbersKeepTheirRequiredTypes() {
    var serializer = IdentitySessionConfiguration.serializer();
    assertThat(serializer.deserialize(serializer.serialize(1234567890123L)))
        .isEqualTo(1234567890123L);
    assertThat(serializer.deserialize(serializer.serialize(1800))).isEqualTo(1800);
  }

  @Test
  void unregisteredPolymorphicClassIsDenied() {
    var serializer = IdentitySessionConfiguration.serializer();
    var failure =
        catchThrowable(
            () ->
                serializer.deserialize(
                    "[\"java.io.File\",\"unexpected\"]".getBytes(StandardCharsets.UTF_8)));
    assertThat(failure)
        .isInstanceOf(org.springframework.data.redis.serializer.SerializationException.class);
    assertThat(failure.getMessage().toLowerCase(java.util.Locale.ROOT)).contains("denied");
  }

  @Test
  void httpsIssuerKeepsCookieSecureHttpOnlyAndHostOnlyRegardlessOfIncomingHeaders() {
    var settings =
        org.springframework.security.oauth2.server.authorization.settings
            .AuthorizationServerSettings.builder()
            .issuer("https://login.example")
            .build();
    var serializer = new IdentitySessionConfiguration().cookieSerializer(settings);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("attacker.example");
    request.addHeader("Forwarded", "proto=http;host=attacker.example");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    serializer.writeCookieValue(
        new org.springframework.session.web.http.CookieSerializer.CookieValue(
            request, response, "fixture-cookie"));
    String header = response.getHeader("Set-Cookie");
    assertThat(header).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
    assertThat(header).doesNotContain("Domain=");
  }
}
