package com.ken.platform.identity.service.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class ProtocolErrorHandlerTest {
  private final SecurityAuditRepository audits = mock(SecurityAuditRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

  private ProtocolErrorHandler handler() {
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    return new ProtocolErrorHandler(audits, manager);
  }

  @Test
  void responseContainsOnlyProtocolCodeAndParseFailureIsAudited() throws Exception {
    var response = new MockHttpServletResponse();
    handler()
        .onAuthenticationFailure(
            new MockHttpServletRequest(),
            response,
            new OAuth2AuthenticationException(
                new OAuth2Error("invalid_client", "secret/code/state must not escape", null)));
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid_client\"}");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    verify(audits)
        .protocolEvent(
            eq("identity.oauth.protocol"),
            eq("FAILURE"),
            isNull(),
            eq("invalid_client"),
            anyString());
  }

  @Test
  void providerFailureAlreadyAuditedIsNotDuplicated() throws Exception {
    var response = new MockHttpServletResponse();
    handler()
        .onAuthenticationFailure(
            new MockHttpServletRequest(),
            response,
            new ProtocolProvider.AuditedFailure("invalid_scope"));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyNoInteractions(audits);
  }

  @Test
  void failedIndependentAuditFailsClosedWithServiceUnavailable() throws Exception {
    doThrow(new DataAccessResourceFailureException("SQL/secret fixture"))
        .when(audits)
        .protocolEvent(anyString(), anyString(), isNull(), anyString(), anyString());
    var response = new MockHttpServletResponse();
    handler()
        .onAuthenticationFailure(
            new MockHttpServletRequest(),
            response,
            new OAuth2AuthenticationException("invalid_request"));
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"temporarily_unavailable\"}");
  }
}
