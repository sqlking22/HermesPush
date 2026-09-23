package com.hermes.push.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
  private final ErrorCode errorCode;
  private final String detail;

  public BizException(ErrorCode ec, String detail) {
    super(ec.getCode() + ": " + ec.getUserMessage()
        + ((detail == null || detail.isBlank()) ? "" : " | " + detail));
    this.errorCode = ec;
    this.detail = (detail == null || detail.isBlank()) ? "" : detail;
  }

  public BizException(ErrorCode ec, Throwable cause) {
    super(ec.getCode() + ": " + ec.getUserMessage()
        + (cause.getMessage() == null ? "" : " | " + cause.getMessage()), cause);
    this.errorCode = ec;
    this.detail = cause.getMessage() == null ? "" : cause.getMessage();
  }

  public BizException(ErrorCode ec) {
    this(ec, "");
  }
}
