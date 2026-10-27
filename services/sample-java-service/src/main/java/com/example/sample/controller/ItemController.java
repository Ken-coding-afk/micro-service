package com.example.sample.controller;

import com.example.sample.controller.dto.ApiResponse;
import com.example.sample.model.Item;
import com.example.sample.model.ItemPage;
import com.example.sample.service.ItemService;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/java-items")
public class ItemController {
  private final ItemService service;

  public ItemController(ItemService service) {
    this.service = service;
  }

  public record CreateItem(@NotBlank @Size(max = 120) String name) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequirePermission(value = "java-item:create", scope = "java.items.write")
  public ApiResponse<Item> create(
      @Valid @RequestBody CreateItem value, @AuthenticationPrincipal CurrentPrincipal principal) {
    return ApiResponse.success(service.create(principal, value.name()));
  }

  @GetMapping
  @RequirePermission(value = "java-item:read", scope = "java.items.read")
  public ApiResponse<ItemPage> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal CurrentPrincipal principal) {
    return ApiResponse.success(service.list(principal, page, size));
  }

  @GetMapping("/peer")
  @RequirePermission(value = "java-item:read", scope = "java.items.read")
  public ResponseEntity<
          com.example.sample.controller.dto.ApiResponse<com.example.sample.model.ItemPage>>
      peer() throws Exception {
    return service.peer();
  }
}
