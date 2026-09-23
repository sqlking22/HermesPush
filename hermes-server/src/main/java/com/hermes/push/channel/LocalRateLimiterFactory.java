package com.hermes.push.channel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存令牌桶限流实现（单机版，默认启用）。
 * <p>key = sha256(webhookUrl) 小写 hex；Bucket 为 synchronized 令牌桶，
 * 容量=limitPerMin，补充速率=limitPerMin/60.0 每秒，按上次补充时间惰性计算。
 * acquire 以 50ms 间隔轮询直至拿到令牌或超 waitTimeoutSec 返回 false。
 */
@Component
@ConditionalOnProperty(name = "hermes.rate-limiter", havingValue = "local", matchIfMissing = true)
public class LocalRateLimiterFactory implements RateLimiterFactory {

  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Override
  public boolean acquire(String webhookUrl, int limitPerMin, int waitTimeoutSec) {
    String key = sha256Hex(webhookUrl);
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(limitPerMin));
    return bucket.tryAcquire(waitTimeoutSec);
  }

  @Override
  public void evict(String webhookUrl) {
    buckets.remove(sha256Hex(webhookUrl));
  }

  static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b & 0xff));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  /**
   * 令牌桶——synchronized 保证线程安全。
   * 容量 capacity 个令牌，补充速率 refillPerSecond 令牌/秒。
   */
  static class Bucket {
    private final double capacity;
    private final double refillPerSecond; // tokens per second
    private double tokens;
    private long lastRefillNanos;

    Bucket(int limitPerMin) {
      this.capacity = limitPerMin;
      this.refillPerSecond = limitPerMin / 60.0;
      this.tokens = limitPerMin; // 初始满桶
      this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryAcquire(int waitTimeoutSec) {
      long deadlineNanos = System.nanoTime() + (long) waitTimeoutSec * 1_000_000_000L;
      while (true) {
        refill();
        if (tokens >= 1.0) {
          tokens -= 1.0;
          return true;
        }
        long now = System.nanoTime();
        if (now >= deadlineNanos) {
          return false;
        }
        // 计算需要等待多久才能攒够 1 个令牌
        double deficit = 1.0 - tokens;
        long waitNanos = (long) (deficit / refillPerSecond * 1_000_000_000.0);
        // 至少等 50ms，最多等到 deadline
        long minWaitNanos = 50_000_000L; // 50ms
        long actualWaitNanos = Math.max(minWaitNanos, Math.min(waitNanos, deadlineNanos - now));
        try {
          Thread.sleep(actualWaitNanos / 1_000_000L, (int) (actualWaitNanos % 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }

    private void refill() {
      long now = System.nanoTime();
      double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
      if (elapsedSec > 0) {
        tokens = Math.min(capacity, tokens + elapsedSec * refillPerSecond);
        lastRefillNanos = now;
      }
    }
  }
}
