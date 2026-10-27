package com.example.sample.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.CallContexts;

public record ApiResponse<T>(
    String code, String message, T data, @JsonProperty("request_id") String requestId) {
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>("OK", "success", data, CallContexts.current().requestId());
  }
}
