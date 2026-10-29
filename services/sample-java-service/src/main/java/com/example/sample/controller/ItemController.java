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
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal CurrentPrincipal principal) {
    if (request.getParameterMap().containsKey("page_size")) {
      throw new com.ken.platform.foundation.http.PlatformException(
          "VALIDATION_ERROR", "分页参数请使用 page 和 size", 400);
    }
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
