package com.hermes.push.channel;

public interface RateLimiterFactory {
  /**
   * 尝试获取令牌，最多等待 waitTimeoutSec 秒。
   * @return true 拿到令牌；false 等待超时
   */
  boolean acquire(String webhookUrl, int limitPerMin, int waitTimeoutSec);

  /** 移除该 webhook 对应的限流器（配置变更时调用，保证新配置生效） */
  void evict(String webhookUrl);
}
