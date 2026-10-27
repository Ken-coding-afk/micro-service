package com.example.sample;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.sample.controller.ItemController;
import com.example.sample.model.Item;
import com.example.sample.model.ItemPage;
import com.example.sample.service.ItemService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ItemControllerTest {
  private com.ken.platform.foundation.http.CallContexts.Scope context;

  @org.junit.jupiter.api.BeforeEach
  void bindContext() {
    context =
        com.ken.platform.foundation.http.CallContexts.bind(
            new com.ken.platform.foundation.http.CallContext(
                "test-request", null, null, null, null, null));
  }

  @org.junit.jupiter.api.AfterEach
  void closeContext() {
    context.close();
  }

  @Test
  void existingJsonFieldsAndStatusesAreKept() throws Exception {
    var service = mock(ItemService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ItemController(service))
            .setCustomArgumentResolvers(
                new org.springframework.security.web.method.annotation
                    .AuthenticationPrincipalArgumentResolver())
            .build();
    var item = new Item(UUID.randomUUID().toString(), "record");
    when(service.create(any(), eq("record"))).thenReturn(item);
    when(service.list(any(), eq(1), eq(20))).thenReturn(new ItemPage(List.of(item), 1, 1, 20));
    // 单独验证协议转换；安全拦截器与真实身份由混合项目回归覆盖。
    mvc.perform(
            post("/api/v1/java-items")
                .contentType("application/json")
                .content("{\"name\":\"record\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("OK"))
        .andExpect(jsonPath("$.data.name").value("record"))
        .andExpect(jsonPath("$.data.id").value(item.id()))
        .andExpect(jsonPath("$.request_id").isString())
        .andExpect(jsonPath("$.requestId").doesNotExist());
    mvc.perform(get("/api/v1/java-items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page_size").value(20))
        .andExpect(jsonPath("$.data.pageSize").doesNotExist())
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(
            post("/api/v1/java-items").contentType("application/json").content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
    when(service.peer())
        .thenReturn(
            ResponseEntity.status(503)
                .body(
                    "{\"code\":\"SERVICE_BUSY\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    mvc.perform(get("/api/v1/java-items/peer"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string("{\"code\":\"SERVICE_BUSY\"}"));
  }
}
