package com.hermes.push.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleBiz(BizException e) {
    log.warn("biz error {}: {}", e.getErrorCode().getCode(), e.getDetail());
    return ResponseEntity.ok((ApiResponse) ApiResponse.fail(e.getErrorCode(), e.getDetail()));
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Map<String,Object>>> handleOther(Exception e) {
    log.error("unhandled", e);
    return ResponseEntity.internalServerError().body((ApiResponse) ApiResponse.fail(ErrorCode.SYS_003, String.valueOf(e.getMessage())));
  }
}
