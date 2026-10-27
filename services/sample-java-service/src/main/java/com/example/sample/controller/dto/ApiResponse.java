package com.example.sample.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.http.CallContexts;

public record ApiResponse<T>(
    @JsonProperty(required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        String code,
    @JsonProperty(required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        String message,
    @JsonProperty(required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        T data,
    @JsonProperty(value = "request_id", required = true)
        @com.fasterxml.jackson.annotation.JsonSetter(
            nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
        String requestId) {
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>("OK", "success", data, CallContexts.current().requestId());
  }
}
