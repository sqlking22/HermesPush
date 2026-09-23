package com.hermes.push.common;

/**
 * 业务异常（M1 最小版本，Task 3 将扩展）。
 * getMessage() 格式：{@code code + ": " + userMessage}
 */
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode() + ": " + errorCode.getUserMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
