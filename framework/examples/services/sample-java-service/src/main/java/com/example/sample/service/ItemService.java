package com.example.sample.service;

import com.example.sample.dal.ItemRepository;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.DataScope;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ItemService {
  private final ItemRepository repository;
  private final TenantTransactions transactions;
  private final com.example.sample.BusinessSettings settings;

  public ItemService(
      ItemRepository repository,
      TenantTransactions transactions,
      com.example.sample.BusinessSettings settings) {
    this.repository = repository;
    this.transactions = transactions;
    this.settings = settings;
  }

  public Map<String, Object> create(CurrentPrincipal principal, String name) {
    var tenant = principal.requireTenant();
    var owner = UUID.fromString(principal.subject());
    var id = UUID.randomUUID();
    return transactions.execute(
        () -> {
          repository.create(id, tenant, owner, name);
          return Map.of("id", id.toString(), "name", name);
        });
  }

  public Map<String, Object> list(CurrentPrincipal principal, int page, int size) {
    var tenant = principal.requireTenant();
    var owner = UUID.fromString(principal.subject());
    boolean own = principal.dataScope("java-item:read") == DataScope.SELF;
    return transactions.execute(
        () -> repository.list(tenant, owner, own, page, Math.min(size, settings.maxPageSize())));
  }
}
