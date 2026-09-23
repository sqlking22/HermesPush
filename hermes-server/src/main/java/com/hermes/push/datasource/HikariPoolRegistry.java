package com.hermes.push.datasource;

import com.hermes.push.security.AesGcmCipher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** 按数据源 ID 缓存 HikariCP 连接池；数据源更新/停用时调用 evict 淘汰旧池。 */
@Component
public class HikariPoolRegistry {
  private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
  private final AesGcmCipher cipher;

  public HikariPoolRegistry(AesGcmCipher cipher) {
    this.cipher = cipher;
  }

  public HikariDataSource getOrCreate(Datasource ds) {
    return pools.computeIfAbsent(ds.getId(), id -> createPool(ds));
  }

  public void evict(Long dsId) {
    HikariDataSource pool = pools.remove(dsId);
    if (pool != null) {
      pool.close();
    }
  }

  private HikariDataSource createPool(Datasource ds) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(ds.getJdbcUrl());
    cfg.setUsername(ds.getUsername());
    cfg.setPassword(cipher.decrypt(ds.getPasswordCipher()));
    cfg.setMaximumPoolSize(ds.getPoolMax() != null ? ds.getPoolMax() : 5);
    cfg.setConnectionTimeout(10_000);
    cfg.setReadOnly(true);
    cfg.setAutoCommit(true);
    cfg.setPoolName("hp-ds-" + ds.getId());
    return new HikariDataSource(cfg);
  }
}
