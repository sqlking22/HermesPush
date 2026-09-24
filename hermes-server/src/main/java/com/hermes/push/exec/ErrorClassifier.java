package com.hermes.push.exec;

import java.util.Set;

/**
 * 错误码重试分类器。
 * <p>DS-002（数据源超时/连接失败）、PUSH-011（渠道限流等待超时）、
 * SYS-005（渲染超时）、NODE_LOST（节点失联）为可重试错误。
 */
public final class ErrorClassifier {

  private static final Set<String> RETRYABLE = Set.of(
      "DS-002", "PUSH-011", "SYS-005", "NODE_LOST");

  private ErrorClassifier() {}

  public static boolean isRetryable(String errorCode) {
    if (errorCode == null) return false;
    return RETRYABLE.contains(errorCode);
  }
}
