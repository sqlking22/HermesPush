package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExecQueueMapper extends BaseMapper<TaskExec> {

  int insertPending(TaskExec exec);

  Long selectClaimableId();

  int markRunning(@Param("id") long id, @Param("nodeId") String nodeId);

  int heartbeat(@Param("id") long id, @Param("nodeId") String nodeId);

  int retryWait(
      @Param("id") long id,
      @Param("errorCode") String errorCode,
      @Param("errorMsg") String errorMsg,
      @Param("backoffSeconds") int backoffSeconds);

  int promoteDueRetries();

  int recoverLostRunningRetry();

  int recoverLostRunningFail();

  int finish(
      @Param("id") long id,
      @Param("status") String status,
      @Param("stageCosts") String stageCosts,
      @Param("rowsTotal") Integer rowsTotal,
      @Param("errorCode") String errorCode,
      @Param("errorMsg") String errorMsg);
}
