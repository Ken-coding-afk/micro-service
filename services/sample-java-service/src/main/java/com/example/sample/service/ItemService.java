package com.example.sample.service;

import com.example.sample.client.PeerClient;
import com.example.sample.dal.ItemRepository;
import com.example.sample.model.Item;
import com.example.sample.model.ItemPage;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.DataScope;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class ItemService {
  private final ItemRepository repository;
  private final PeerClient peer;
  private final TenantTransactions transactions;
  private final com.example.sample.BusinessSettings settings;

  public ItemService(
      ItemRepository repository,
      TenantTransactions transactions,
      com.example.sample.BusinessSettings settings,
      PeerClient peer) {
    this.repository = repository;
    this.transactions = transactions;
    this.settings = settings;
    this.peer = peer;
  }

  public Item create(CurrentPrincipal principal, String name) {
    com.ken.platform.foundation.http.CallContexts.checkDeadline();
    var tenant = principal.requireTenant();
    var owner = UUID.fromString(principal.subject());
    var id = UUID.randomUUID();
    return transactions.execute(
        () -> {
          com.ken.platform.foundation.http.CallContexts.checkDeadline();
          repository.create(id, tenant, owner, name);
          return new Item(id.toString(), name);
        });
  }

  public ItemPage list(CurrentPrincipal principal, int page, int size) {
    com.ken.platform.foundation.http.CallContexts.checkDeadline();
    var tenant = principal.requireTenant();
    var owner = UUID.fromString(principal.subject());
    boolean own = principal.dataScope("java-item:read") == DataScope.SELF;
    return transactions.execute(
        () -> repository.list(tenant, owner, own, page, Math.min(size, settings.maxPageSize())));
  }

  public ResponseEntity<
          com.example.sample.controller.dto.ApiResponse<com.example.sample.model.ItemPage>>
      peer() throws Exception {
    com.ken.platform.foundation.http.CallContexts.checkDeadline();
    return peer.list();
  }
}
