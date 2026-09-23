package com.hermes.push.common;

/**
 * 错误码枚举（M1 最小版本，Task 3 将扩展）。
 * 消息约定：{@code code + ": " + userMessage}，与 BizException 保持一致。
 */
public enum ErrorCode {
    SYS_001_CRYPTO("SYS-001", "加解密失败", "");

    private final String code;
    private final String userMessage;
    private final String suggestion;

    ErrorCode(String code, String userMessage, String suggestion) {
        this.code = code;
        this.userMessage = userMessage;
        this.suggestion = suggestion;
    }

    public String getCode() { return code; }
    public String getUserMessage() { return userMessage; }
    public String getSuggestion() { return suggestion; }
}
