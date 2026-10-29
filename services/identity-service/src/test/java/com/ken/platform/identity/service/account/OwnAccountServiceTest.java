package com.ken.platform.identity.service.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.identity.dal.dataobject.login.LoginRealm;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.login.PasswordLoginService;
import com.ken.platform.identity.service.oauth.ApplicationConsentService;
import com.ken.platform.identity.service.oauth.UserTokenService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OwnAccountServiceTest {
  private final PasswordLoginService logins = mock(PasswordLoginService.class);
  private final ApplicationConsentService consents = mock(ApplicationConsentService.class);
  private final ManagementAudits audits = mock(ManagementAudits.class);
  private final OwnAccountService service = new OwnAccountService(logins, consents, audits);
  private final PasswordLoginService.Identity owner =
      new PasswordLoginService.Identity(
          UUID.randomUUID(), LoginRealm.TENANT, UUID.randomUUID(), UUID.randomUUID(), 7, 3L, false);

  @BeforeEach
  void auditBoundary() {
    when(audits.attempt(any(), any(), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(2).get());
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  private void authenticate(
      String client,
      String subject,
      UUID tenant,
      Set<String> scopes,
      Object identity,
      boolean audience) {
    var attributes = new HashMap<String, Object>();
    if (identity != null) attributes.put(UserTokenService.OWN_IDENTITY, identity);
    attributes.put(
        UserTokenService.APPLICATION_VIEW,
        new UserTokenService.ApplicationView(
            "APPLICATION_EFFECTIVE",
            subject,
            "user",
            tenant,
            client,
            Map.of(),
            audience
                ? Map.of(
                    "identity-service",
                    new UserTokenService.ApplicationPermissions(
                        Set.of("profile"), Set.of(), Map.of()))
                : Map.of(),
            Instant.now()));
    var principal =
        new CurrentPrincipal(
            subject, "user", tenant, client, scopes, Set.of(), Map.of(), Set.of(), attributes);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
  }

  private void authenticate() {
    authenticate(
        "identity-admin-tenant",
        owner.user().toString(),
        owner.tenant(),
        Set.of("profile"),
        owner,
        true);
  }

  @Test
  void genuineManagementTokenUsesExactOriginalSsoAndRestoresBearerContext() {
    authenticate();
    var previous = SecurityContextHolder.getContext();
    when(logins.ownSessions(20, 20))
        .thenAnswer(
            ignored -> {
              var current = SecurityContextHolder.getContext().getAuthentication();
              assertThat(current.getPrincipal()).isSameAs(owner);
              assertThat(current.getAuthorities()).isEmpty();
              return List.of();
            });
    assertThat(service.sessions(1, 20)).isEmpty();
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
  }

  @Test
  void thirdPartyAndBusinessProfileNeverBecomeAccountControlEvenWithCopiedInternalAttribute() {
    for (String client : List.of("third-party", "web-bff", "identity-admin-platform")) {
      authenticate(client, owner.user().toString(), owner.tenant(), Set.of("profile"), owner, true);
      assertThatThrownBy(service::signOutAll)
          .isInstanceOf(PlatformException.class)
          .extracting("status")
          .isEqualTo(403);
    }
    verifyNoInteractions(logins, consents);
  }

  @Test
  void missingOrMismatchedOriginalIdentityCannotSelectAnotherAccountOrTenant() {
    authenticate(
        "identity-admin-tenant",
        owner.user().toString(),
        owner.tenant(),
        Set.of("profile"),
        null,
        true);
    assertThatThrownBy(() -> service.sessions(0, 20)).isInstanceOf(PlatformException.class);
    authenticate(
        "identity-admin-tenant",
        UUID.randomUUID().toString(),
        owner.tenant(),
        Set.of("profile"),
        owner,
        true);
    assertThatThrownBy(service::signOutAll).isInstanceOf(PlatformException.class);
    authenticate(
        "identity-admin-tenant",
        owner.user().toString(),
        UUID.randomUUID(),
        Set.of("profile"),
        owner,
        true);
    assertThatThrownBy(service::signOutAll).isInstanceOf(PlatformException.class);
    authenticate(
        "identity-admin-tenant", owner.user().toString(), owner.tenant(), Set.of(), owner, true);
    assertThatThrownBy(service::signOutAll).isInstanceOf(PlatformException.class);
    authenticate(
        "identity-admin-tenant",
        owner.user().toString(),
        owner.tenant(),
        Set.of("profile"),
        owner,
        false);
    assertThatThrownBy(service::signOutAll).isInstanceOf(PlatformException.class);
    verifyNoInteractions(logins, consents);
  }

  @Test
  void coreRevocationClearingOrFailingCannotLeakSsoAuthenticationToNextRequest() {
    authenticate();
    var previous = SecurityContextHolder.getContext();
    doAnswer(
            ignored -> {
              SecurityContextHolder.clearContext();
              return null;
            })
        .when(logins)
        .changeOwnPassword(anyString(), anyString());
    service.changePassword("old", "new");
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    doAnswer(
            ignored -> {
              SecurityContextHolder.clearContext();
              throw new PlatformException("DEPENDENCY_UNAVAILABLE", "不可用", 503);
            })
        .when(logins)
        .revokeOwnSession(any());
    assertThatThrownBy(() -> service.revokeSession(UUID.randomUUID()))
        .isInstanceOf(PlatformException.class);
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
  }

  @Test
  void boundedPaginationNeverOverflowsOrCallsCoreWithUnboundedLimit() {
    authenticate();
    assertThatThrownBy(() -> service.sessions(10001, 100)).isInstanceOf(PlatformException.class);
    assertThatThrownBy(() -> service.consents(-1, 1)).isInstanceOf(PlatformException.class);
    assertThatThrownBy(() -> service.sessions(0, 101)).isInstanceOf(PlatformException.class);
    verifyNoInteractions(logins, consents);
  }
}
