package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExecPushMapper extends BaseMapper<TaskExecPush> {

  int upsertSuccess(@Param("execId") long execId, @Param("channelId") long channelId,
      @Param("artifactKey") String artifactKey, @Param("msgType") String msgType);

  int upsertFailed(@Param("execId") long execId, @Param("channelId") long channelId,
      @Param("artifactKey") String artifactKey, @Param("msgType") String msgType,
      @Param("errorCode") String errorCode, @Param("errorMsg") String errorMsg,
      @Param("retryIncrement") int retryIncrement);
}
