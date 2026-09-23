package com.hermes.push.channel;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.hermes.push.channel.LocalRateLimiterFactory.sha256Hex;

/**
 * 基于 Redisson 的分布式限流实现（生产多节点部署使用）。
 * <p>key = {@code hermes:rl:} + sha256(webhookUrl) 小写 hex；
 * RRateLimiter 模式 OVERALL，速率 limitPerMin 次 / 60 秒。
 * 测试 profile 下 Redisson 自动配置已排除，本 bean 不会实例化。
 */
@Component
@ConditionalOnProperty(name = "hermes.rate-limiter", havingValue = "redis")
public class RedissonRateLimiterFactory implements RateLimiterFactory {

  private static final String KEY_PREFIX = "hermes:rl:";

  private final RedissonClient redisson;
  private final ConcurrentHashMap<String, RRateLimiter> cache = new ConcurrentHashMap<>();

  public RedissonRateLimiterFactory(RedissonClient redisson) {
    this.redisson = redisson;
  }

  @Override
  public boolean acquire(String webhookUrl, int limitPerMin, int waitTimeoutSec) {
    String key = KEY_PREFIX + sha256Hex(webhookUrl);
    RRateLimiter limiter = cache.computeIfAbsent(key, k -> {
      RRateLimiter rl = redisson.getRateLimiter(k);
      rl.trySetRate(RateType.OVERALL, limitPerMin, 60, RateIntervalUnit.SECONDS);
      return rl;
    });
    return limiter.tryAcquire(1, waitTimeoutSec, TimeUnit.SECONDS);
  }

  @Override
  public void evict(String webhookUrl) {
    String key = KEY_PREFIX + sha256Hex(webhookUrl);
    RRateLimiter removed = cache.remove(key);
    if (removed != null) {
      try {
        removed.delete();
      } catch (Exception ignored) {
        // 清理失败不影响业务
      }
    }
  }
}
