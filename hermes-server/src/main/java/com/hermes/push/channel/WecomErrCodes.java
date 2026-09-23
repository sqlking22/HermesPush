package com.hermes.push.channel;

/**
 * 企微机器人 errcode 分类常量。
 * <p>0=成功；45009=接口超频（可重试 PUSH-011）；93000/40001=webhook 无效（不可重试 PUSH-012）；
 * 其余非零 errcode 统一归为 PUSH-012（不可重试，detail 透传 errcode+errmsg）。
 */
public final class WecomErrCodes {
  private WecomErrCodes() {}

  public static final int SUCCESS = 0;
  /** 接口调用超频（retryable → PUSH-011） */
  public static final int API_FREQ_LIMIT = 45009;
  /** invalid webhook url（not retryable → PUSH-012） */
  public static final int INVALID_WEBHOOK = 93000;
  /** invalid credential / access_token 失效（not retryable → PUSH-012） */
  public static final int INVALID_TOKEN = 40001;

  public static boolean isSuccess(int errcode) { return errcode == SUCCESS; }
  public static boolean isRetryable(int errcode) { return errcode == API_FREQ_LIMIT; }
  public static boolean isInvalidWebhook(int errcode) {
    return errcode == INVALID_WEBHOOK || errcode == INVALID_TOKEN;
  }
}
