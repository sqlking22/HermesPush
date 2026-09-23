package com.hermes.push.channel;

public record PushResult(boolean success, boolean retryable, String errorCode, String errorMsg) {}
