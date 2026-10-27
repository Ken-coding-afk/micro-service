package com.ken.platform.identity.service.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class RecentAuthenticationTest {
  private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
  private final SecurityPolicies policies = mock(SecurityPolicies.class);
  private final RecentAuthentication recent =
      new RecentAuthentication(policies, Clock.fixed(NOW, ZoneOffset.UTC));

  private void policy(int seconds) {
    when(policies.current(null))
        .thenReturn(
            new SecurityPolicies.Effective(
                15, 5, 900, 900, 100, 60, 28800, 1800, seconds, 0, null));
  }

  private void actor(Map<String, Object> attributes) {
    var principal = new DefaultOAuth2AuthenticatedPrincipal("platform-test", attributes, List.of());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  private void denied() {
    assertThatThrownBy(() -> recent.require(null))
        .isInstanceOfSatisfying(
            PlatformException.class,
            error -> assertThat(error.code()).isEqualTo("RECENT_AUTHENTICATION_REQUIRED"));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(longs = {300, 301, 10000, -1})
  void rejectsExpiredBoundaryAndFutureAuthentication(long age) {
    policy(300);
    actor(Map.of(UserTokenService.AUTHENTICATED_AT, NOW.minusSeconds(age)));
    denied();
  }

  @Test
  void acceptsOnlyCurrentVerifiedAuthenticationAndCurrentPolicy() {
    policy(300);
    actor(Map.of(UserTokenService.AUTHENTICATED_AT, NOW.minusSeconds(299)));
    recent.require(null);
    policy(120);
    denied();
  }

  @Test
  void absentAuthenticationOrOnlyFreshTokenIatCannotActAsRecentLogin() {
    policy(300);
    denied();
    actor(Map.of("iat", NOW));
    denied();
  }
}
