package com.hermes.push.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Service;
import java.net.URI;

/**
 * webhook 白名单校验服务。
 * <p>从 hp_whitelist 表查询 type=WEBHOOK_HOST 的精确匹配记录。
 */
@Service
public class WhitelistService {

  private static final String TYPE_WEBHOOK_HOST = "WEBHOOK_HOST";

  private final WhitelistMapper mapper;

  public WhitelistService(WhitelistMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 校验 webhook 域名是否在白名单中，不在则抛出 BizException(SYS_002)。
   */
  public void assertWebhookAllowed(String url) {
    String host = extractHost(url);
    if (!isAllowed(host)) {
      throw new BizException(ErrorCode.SYS_002, "webhook 域名不在白名单: " + host);
    }
  }

  /**
   * 判断指定 host 是否在 WEBHOOK_HOST 白名单中。
   */
  public boolean isAllowed(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    Long count = mapper.selectCount(new QueryWrapper<Whitelist>()
        .eq("type", TYPE_WEBHOOK_HOST)
        .eq("value", host));
    return count != null && count > 0;
  }

  private String extractHost(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        throw new BizException(ErrorCode.SYS_002, "webhook URL 无法解析域名: " + url);
      }
      return host;
    } catch (IllegalArgumentException e) {
      throw new BizException(ErrorCode.SYS_002, "webhook URL 格式无效: " + url);
    }
  }
}
