package com.hermes.push.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
  GlobalExceptionHandler h = new GlobalExceptionHandler();

  @Test void bizExceptionMapsToCodeAndSuggestion() {
    var r = h.handleBiz(new BizException(ErrorCode.DS_002, "connect timed out"));
    var body = r.getBody();
    assertThat(body.code()).isNotZero();
    assertThat(body.data().get("errorCode")).isEqualTo("DS-002");
    assertThat((String) body.data().get("suggestion")).contains("只读账号");
    assertThat((String) body.data().get("detail")).contains("connect timed out");
  }
  @Test void okResponseHasZeroCode() {
    assertThat(ApiResponse.ok("x").code()).isZero();
  }
}
