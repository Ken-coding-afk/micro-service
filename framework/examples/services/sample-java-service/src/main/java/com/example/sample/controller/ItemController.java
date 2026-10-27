package com.example.sample.controller;

import com.example.sample.service.ItemService;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.http.ServiceHttpClient;
import com.ken.platform.foundation.security.CurrentPrincipal;
import com.ken.platform.foundation.security.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/java-items")
public class ItemController {
  private final ItemService service;
  private final ServiceHttpClient http;
  private final Environment environment;

  public ItemController(ItemService service, ServiceHttpClient http, Environment environment) {
    this.service = service;
    this.http = http;
    this.environment = environment;
  }

  public record CreateItem(@NotBlank @Size(max = 120) String name) {}

  private Map<String, Object> response(Object data) {
    return Map.of(
        "code",
        "OK",
        "message",
        "success",
        "data",
        data,
        "request_id",
        CallContexts.current().requestId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequirePermission(value = "java-item:create", scope = "java.items.write")
  public Map<String, Object> create(
      @Valid @RequestBody CreateItem value, @AuthenticationPrincipal CurrentPrincipal principal) {
    return response(service.create(principal, value.name()));
  }

  @GetMapping
  @RequirePermission(value = "java-item:read", scope = "java.items.read")
  public Map<String, Object> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal CurrentPrincipal principal) {
    return response(service.list(principal, page, size));
  }

  @GetMapping("/peer")
  @RequirePermission(value = "java-item:read", scope = "java.items.read")
  public ResponseEntity<byte[]> peer() throws Exception {
    if (!"sample-python-service".equals(environment.getProperty("PEER_SERVICE")))
      throw new PlatformException("PEER_NOT_CONFIGURED", "未启用 Python 协作示例", 503);
    return http.request(
            "sample-python-service", "GET", "/api/v1/python-items", null, CallContexts.current())
        .get();
  }
}
