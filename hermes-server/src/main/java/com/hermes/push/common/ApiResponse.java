package com.hermes.push.common;

import java.util.Map;
import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String requestId) {
  public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "ok", data, UUID.randomUUID().toString().substring(0,8)); }
  @SuppressWarnings("unchecked")
  public static ApiResponse<Map<String,Object>> fail(ErrorCode ec, String detail) {
    return new ApiResponse<>(500, ec.getUserMessage(),
        Map.of("errorCode", ec.getCode(), "suggestion", ec.getSuggestion(), "detail", detail == null ? "" : detail),
        UUID.randomUUID().toString().substring(0,8));
  }
}
