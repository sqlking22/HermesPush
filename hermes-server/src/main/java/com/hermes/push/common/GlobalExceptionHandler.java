package com.hermes.push.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleBiz(BizException e) {
    log.warn("biz error {}: {}", e.getErrorCode().getCode(), e.getDetail());
    return ResponseEntity.ok(ApiResponse.fail(e.getErrorCode(), e.getDetail()));
  }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleValidation(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + ": " + f.getDefaultMessage())
        .collect(Collectors.joining("; "));
    log.warn("validation error: {}", detail);
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.SYS_006, detail));
  }
  @ExceptionHandler(BindException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleBind(BindException e) {
    String detail = e.getFieldErrors().stream()
        .map(f -> f.getField() + ": " + f.getDefaultMessage())
        .collect(Collectors.joining("; "));
    log.warn("bind error: {}", detail);
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.SYS_006, detail));
  }
  @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleNotLogin(cn.dev33.satoken.exception.NotLoginException e) {
    log.warn("not login: {}", e.getType());
    return ResponseEntity.status(401).body(ApiResponse.fail(ErrorCode.AUTH_002, e.getType()));
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleOther(Exception e) {
    log.error("unhandled", e);
    return ResponseEntity.internalServerError().body(ApiResponse.fail(ErrorCode.SYS_003, String.valueOf(e.getMessage())));
  }
}
