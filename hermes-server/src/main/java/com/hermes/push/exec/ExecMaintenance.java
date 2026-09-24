package com.hermes.push.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hermes.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class ExecMaintenance {

  private static final Logger log = LoggerFactory.getLogger(ExecMaintenance.class);

  private final ExecQueueMapper mapper;

  public ExecMaintenance(ExecQueueMapper mapper) {
    this.mapper = mapper;
  }

  @Scheduled(fixedDelay = 10_000)
  public void promoteRetries() {
    int count = mapper.promoteDueRetries();
    if (count > 0) {
      log.info("promoteDueRetries: promoted {} retry tasks to PENDING", count);
    }
  }

  @Scheduled(fixedDelay = 30_000)
  public int recoverLostRunning() {
    int retry = mapper.recoverLostRunningRetry();
    if (retry > 0) {
      log.warn("recoverLostRunningRetry: recovered {} lost RUNNING tasks to RETRY_WAIT", retry);
    }
    int fail = mapper.recoverLostRunningFail();
    if (fail > 0) {
      log.warn("recoverLostRunningFail: marked {} lost RUNNING tasks as FAILED (retry exhausted)", fail);
    }
    return retry + fail;
  }
}
