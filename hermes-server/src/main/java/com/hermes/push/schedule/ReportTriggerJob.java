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
import java.util.Map;

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

      // 合并查询：一次 DB 往返取 status + jitter_enabled
      Map<String, Object> row = jdbc.queryForMap(
          "SELECT status, jitter_enabled FROM hp_task WHERE id=?", taskId);
      String status = (String) row.get("status");
      boolean jitterEnabled = Boolean.TRUE.equals(row.get("jitter_enabled"))
          || Integer.valueOf(1).equals(row.get("jitter_enabled"));

      if (!manual && !"ONLINE".equals(status)) {
        log.info("task {} not ONLINE, skip", taskId);
        return;
      }

      int offset = eff.config().schedule().bizOffsetDays() == null ? -1 : eff.config().schedule().bizOffsetDays();

      // 合并查询：一次 DB 往返取 today + bizDate
      Map<String, Object> dates = jdbc.queryForMap(
          "SELECT CURDATE() AS today, CURDATE() + INTERVAL ? DAY AS biz_date", offset);
      LocalDate today = ((java.sql.Date) dates.get("today")).toLocalDate();
      LocalDate bizDate = ((java.sql.Date) dates.get("biz_date")).toLocalDate();

      LocalDateTime fire;
      Date scheduled = ctx.getScheduledFireTime();
      if (scheduled != null) {
        fire = scheduled.toInstant().atZone(SH).toLocalDateTime();
      } else {
        fire = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
      }

      if (jitterEnabled) {
        fire = fire.plusSeconds(jitter.offsetSeconds(taskId, today));
      }

      Long execId = queue.insertPending(taskId, eff.taskVersionId(), TriggerType.CRON,
          ExecPriority.CRON, fire, bizDate, "{}", null);
      if (execId == null) {
        log.info("task {} fire_time={} 重复触发，已由唯一索引防重跳过", taskId, fire);
      } else {
        log.info("task {} enqueued exec={} fire={}", taskId, execId, fire);
      }
    } catch (Exception e) {
      log.error("trigger job failed task={}", taskId, e);
    }
  }
}
