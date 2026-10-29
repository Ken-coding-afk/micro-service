package com.example.sample;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.sample.dal.ItemRepository;
import com.example.sample.model.Item;
import com.example.sample.model.ItemPage;
import com.example.sample.service.ItemService;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.DataScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ItemServiceTest {
  static CurrentPrincipal user(UUID tenant) {
    return new CurrentPrincipal(
        UUID.randomUUID().toString(),
        "user",
        tenant,
        "fixture",
        Set.of(),
        Set.of("java-item:read"),
        Map.of("java-item:read", DataScope.SELF),
        Set.of(),
        Map.of());
  }

  @Test
  void trustedIdentityPageLimitAndErrorsArePreserved() {
    var repository = mock(ItemRepository.class);
    var transactions = mock(TenantTransactions.class);
    when(transactions.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    var service =
        new ItemService(
            repository,
            transactions,
            new BusinessSettings(30),
            mock(com.example.sample.client.PeerClient.class));
    var principal = user(UUID.randomUUID());
    Item created = service.create(principal, "record");
    assertThat(created.name()).isEqualTo("record");
    verify(repository)
        .create(
            UUID.fromString(created.id()),
            principal.tenantId(),
            UUID.fromString(principal.subject()),
            "record");
    var page = new ItemPage(List.of(created), 1, 2, 30, false);
    when(repository.list(principal.tenantId(), UUID.fromString(principal.subject()), true, 2, 30))
        .thenReturn(page);
    assertThat(service.list(principal, 2, 100)).isEqualTo(page);
    doThrow(new IllegalStateException("database fixture failure"))
        .when(repository)
        .create(any(), any(), any(), eq("failure"));
    assertThatThrownBy(() -> service.create(principal, "failure"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database fixture failure");
  }

  @Test
  void missingTenantRejectsBeforeOpeningTransaction() {
    var repository = mock(ItemRepository.class);
    var transactions = mock(TenantTransactions.class);
    var service =
        new ItemService(
            repository,
            transactions,
            new BusinessSettings(100),
            mock(com.example.sample.client.PeerClient.class));
    assertThatThrownBy(() -> service.create(user(null), "record"))
        .isInstanceOf(com.ken.platform.foundation.http.PlatformException.class);
    verifyNoInteractions(repository, transactions);
  }
}
