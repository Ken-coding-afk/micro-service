package com.ken.platform.identity.service.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ManagementAuditsTest {
  @Test
  void deniedManagementActionUsesIndependentTransactionAndSanitizedDetails() {
    var repository = mock(SecurityAuditRepository.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var audits = new ManagementAudits(repository, manager);
    assertThatThrownBy(
            () ->
                audits.attempt(
                    ManagementAudits.Action.ROLE_PERMISSIONS,
                    null,
                    () -> {
                      throw new IllegalArgumentException("password=private");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository)
        .failure(
            isNull(),
            eq("ANONYMOUS"),
            isNull(),
            eq("identity.role.permissions"),
            isNull(),
            anyString(),
            argThat(value -> value.contains("INTERNAL_ERROR") && !value.contains("private")));
    verify(manager)
        .getTransaction(
            argThat(
                value ->
                    value.getPropagationBehavior()
                        == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_REQUIRES_NEW));
    verify(manager).commit(any());
  }
}
