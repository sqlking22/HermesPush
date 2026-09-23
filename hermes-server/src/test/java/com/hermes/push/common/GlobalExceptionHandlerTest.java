package com.hermes.push.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
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
  @Test void bindExceptionMapsToSys006WithFieldDetails() {
    var br = new BeanPropertyBindingResult(new Object(), "target");
    br.addError(new org.springframework.validation.FieldError("target", "name", "不能为空"));
    br.addError(new org.springframework.validation.FieldError("target", "type", "不能为null"));
    var r = h.handleBind(new BindException(br));
    var body = r.getBody();
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(body.data().get("errorCode")).isEqualTo("SYS-006");
    assertThat((String) body.data().get("detail")).contains("name").contains("不能为空").contains("type");
  }
}
