package com.hermes.push.schedule;

import com.hermes.push.exec.ExecPriority;
import com.hermes.push.exec.ExecQueueRepository;
import com.hermes.push.exec.TriggerType;
import com.hermes.push.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
@DisallowConcurrentExecution
public class ReportTriggerJob implements Job {
  private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

  private final TaskService tasks;
  private final ExecQueueRepository queue;
  private final JdbcTemplate jdbc;
  private final JitterCalculator jitter;

  @Override
  public void execute(JobExecutionContext ctx) {
    var data = ctx.getMergedJobDataMap();
    Long taskId = data.getLong("taskId");
    boolean manual = Boolean.TRUE.equals(data.get("manual")) || "true".equals(data.getString("manual"));
    try {
      var eff = tasks.loadEffectiveConfig(taskId);
      if (!manual) {
        String status = jdbc.queryForObject("SELECT status FROM hp_task WHERE id=?", String.class, taskId);
        if (!"ONLINE".equals(status)) {
          log.info("task {} not ONLINE, skip", taskId);
          return;
        }
      }
      int offset = eff.config().schedule().bizOffsetDays() == null ? -1 : eff.config().schedule().bizOffsetDays();
      LocalDate bizDate = jdbc.queryForObject("SELECT CURDATE() + INTERVAL ? DAY", LocalDate.class, offset);
      LocalDate today = jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);

      LocalDateTime fire;
      Date scheduled = ctx.getScheduledFireTime();
      if (scheduled != null) {
        fire = scheduled.toInstant().atZone(SH).toLocalDateTime();
      } else {
        fire = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
      }

      Boolean jitterEnabled = jdbc.queryForObject(
          "SELECT jitter_enabled FROM hp_task WHERE id=?", Boolean.class, taskId);
      if (Boolean.TRUE.equals(jitterEnabled)) {
        fire = fire.plusSeconds(jitter.offsetSeconds(taskId, today));
      }

      Long execId = queue.insertPending(taskId, eff.taskVersionId(), TriggerType.CRON,
          ExecPriority.CRON, fire, bizDate, "{}", null);
      log.info("task {} enqueued exec={} fire={}", taskId, execId, fire);
    } catch (Exception e) {
      log.error("trigger job failed task={}", taskId, e);
    }
  }
}
