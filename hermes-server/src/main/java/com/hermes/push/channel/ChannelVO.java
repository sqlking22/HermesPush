package com.hermes.push.channel;

import java.net.URI;

public record ChannelVO(
    Long id,
    String name,
    String type,
    String webhookMasked,
    int rateLimitPerMin,
    int waitTimeoutSec,
    boolean testFlag,
    String status
) {
  /**
   * 构造脱敏 webhook：https://{host}/…{原URL最后4字符}
   */
  static String maskWebhook(String webhook) {
    if (webhook == null || webhook.isBlank()) return "";
    try {
      URI uri = URI.create(webhook);
      String host = uri.getHost() == null ? "" : uri.getHost();
      String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
      String last4 = webhook.length() >= 4
          ? webhook.substring(webhook.length() - 4)
          : webhook;
      return scheme + "://" + host + "/…" + last4;
    } catch (Exception e) {
      // 解析失败时返回占位，不泄露明文
      return "***";
    }
  }
}
