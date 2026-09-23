package com.hermes.push.exec;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class ExecQueueRepository {

  private final ExecQueueMapper mapper;

  public ExecQueueRepository(ExecQueueMapper mapper) {
    this.mapper = mapper;
  }

  public Long insertPending(long taskId, long taskVersionId, TriggerType trig, int priority,
      LocalDateTime fireTime, LocalDate bizDate, String paramsJson, String idemKey) {
    TaskExec exec = new TaskExec();
    exec.setTaskId(taskId);
    exec.setTaskVersionId(taskVersionId);
    exec.setTriggerType(trig.name());
    exec.setPriority(priority);
    exec.setFireTime(fireTime);
    exec.setBizDate(bizDate);
    exec.setParamsJson(paramsJson);
    exec.setIdempotencyKey(idemKey);
    int rows = mapper.insertPending(exec);
    return rows > 0 ? exec.getId() : null;
  }

  @Transactional
  public Optional<Long> claim(String nodeId) {
    Long id = mapper.selectClaimableId();
    if (id == null) {
      return Optional.empty();
    }
    int updated = mapper.markRunning(id, nodeId);
    return updated > 0 ? Optional.of(id) : Optional.empty();
  }

  public boolean heartbeat(long execId, String nodeId) {
    return mapper.heartbeat(execId, nodeId) > 0;
  }

  public void retryWait(long execId, String errorCode, String errorMsg, int backoffSeconds) {
    mapper.retryWait(execId, errorCode, errorMsg, backoffSeconds);
  }

  public void finish(long execId, ExecStatus status, String stageCostsJson, Integer rowsTotal,
      String errorCode, String errorMsg) {
    mapper.finish(execId, status.name(), stageCostsJson, rowsTotal, errorCode, errorMsg);
  }

  public int promoteDueRetries() {
    return mapper.promoteDueRetries();
  }

  public TaskExec getById(long execId) {
    return mapper.selectById(execId);
  }
}
