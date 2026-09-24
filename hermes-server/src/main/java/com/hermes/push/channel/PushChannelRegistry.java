package com.hermes.push.channel;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推送渠道注册表。按 type() 键登记所有 PushChannel 实现。
 *
 * <p>若多个实现声明相同 type，取第一个并记录 warn（M1 阶段单实现无冲突）。
 */
@Component
public class PushChannelRegistry {
  private static final Logger log = LoggerFactory.getLogger(PushChannelRegistry.class);

  private final Map<String, PushChannel> channels;

  public PushChannelRegistry(List<PushChannel> channelList) {
    this.channels = new HashMap<>();
    for (PushChannel ch : channelList) {
      String type = ch.type();
      if (channels.containsKey(type)) {
        log.warn("PushChannel type 冲突: {} 已有实现 {}, 跳过 {}", type, channels.get(type).getClass().getSimpleName(), ch.getClass().getSimpleName());
        continue;
      }
      channels.put(type, ch);
    }
  }

  public PushChannel get(String type) {
    PushChannel ch = channels.get(type);
    if (ch == null) {
      throw new BizException(ErrorCode.SYS_003, "渠道类型不存在: " + type);
    }
    return ch;
  }
}
