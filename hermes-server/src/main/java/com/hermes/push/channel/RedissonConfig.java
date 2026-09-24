package com.hermes.push.channel;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redis 限流模式下手动构造 RedissonClient。
 * <p>Redisson starter 的自动配置已在 application.yaml 中全局排除，
 * 避免 local 限流模式下因无 Redis 导致启动失败。
 * 仅当 hermes.rate-limiter=redis 时才创建 RedissonClient bean，
 * 供 RedissonRateLimiterFactory 注入使用。
 */
@Configuration
@ConditionalOnProperty(name = "hermes.rate-limiter", havingValue = "redis")
public class RedissonConfig {

  @Value("${spring.data.redis.host:localhost}")
  private String host;

  @Value("${spring.data.redis.port:6379}")
  private int port;

  @Value("${spring.data.redis.password:}")
  private String password;

  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient() {
    Config config = new Config();
    String address = "redis://" + host + ":" + port;
    config.useSingleServer().setAddress(address);
    if (StringUtils.hasText(password)) {
      config.useSingleServer().setPassword(password);
    }
    return Redisson.create(config);
  }
}
