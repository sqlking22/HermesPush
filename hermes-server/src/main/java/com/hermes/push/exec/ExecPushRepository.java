package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;

/**
 * 推送执行记录仓储——四元组幂等（exec_id + channel_id + artifact_key + msg_type）。
 * <p>基于 uk_push 唯一键的 INSERT ... ON DUPLICATE KEY UPDATE 实现 upsert。
 */
@Repository
public class ExecPushRepository {

  private final ExecPushMapper mapper;

  public ExecPushRepository(ExecPushMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 判断指定四元组是否已有 SUCCESS 状态的记录（幂等判定）。
   */
  public boolean existsSuccess(long execId, long channelId, String artifactKey, String msgType) {
    Long count = mapper.selectCount(new QueryWrapper<TaskExecPush>()
        .eq("exec_id", execId)
        .eq("channel_id", channelId)
        .eq("artifact_key", artifactKey)
        .eq("msg_type", msgType)
        .eq("status", "SUCCESS"));
    return count != null && count > 0;
  }

  /**
   * 标记推送成功（upsert，幂等）。
   */
  public void markSuccess(long execId, long channelId, String artifactKey, String msgType) {
    mapper.upsertSuccess(execId, channelId, artifactKey, msgType);
  }

  /**
   * 标记推送失败（upsert，retry_count 累加 retryIncrement）。
   */
  public void markFailed(long execId, long channelId, String artifactKey, String msgType,
      String errorCode, String errorMsg, int retryIncrement) {
    mapper.upsertFailed(execId, channelId, artifactKey, msgType, errorCode, errorMsg, retryIncrement);
  }
}
