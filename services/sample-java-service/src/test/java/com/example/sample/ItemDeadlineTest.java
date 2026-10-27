package com.example.sample;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.sample.dal.ItemRepository;
import com.example.sample.service.ItemService;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ItemDeadlineTest {
  @Test
  void expiredWorkNeverStartsTransactionOrWrites() {
    var repository = mock(ItemRepository.class);
    var transactions = mock(TenantTransactions.class);
    var service = new ItemService(repository, transactions, null, null);
    try (var scope =
        CallContexts.bind(
            new CallContext("expired", null, null, null, null, Instant.now().minusSeconds(1)))) {
      assertThatThrownBy(() -> service.create(null, "item")).isInstanceOf(PlatformException.class);
      verifyNoInteractions(repository, transactions);
    }
  }
}
