package com.hermes.push.schedule;

import com.hermes.push.task.ScheduleSyncPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Service;
import java.util.TimeZone;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleSyncService implements ScheduleSyncPort {
  private static final String GROUP = "hermes";
  private static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

  private final Scheduler scheduler;

  private static JobKey jobKey(Long taskId) {
    return JobKey.jobKey("hermes-task-" + taskId, GROUP);
  }

  private static TriggerKey triggerKey(Long taskId) {
    return TriggerKey.triggerKey("hermes-task-" + taskId, GROUP);
  }

  @Override
  public void onPublish(Long taskId, String cron) {
    JobKey jk = jobKey(taskId);
    TriggerKey tk = triggerKey(taskId);
    try {
      Trigger trigger = TriggerBuilder.newTrigger()
          .withIdentity(tk)
          .withSchedule(CronScheduleBuilder.cronSchedule(cron)
              .inTimeZone(SH)
              .withMisfireHandlingInstructionDoNothing())
          .build();
      if (scheduler.checkExists(jk)) {
        scheduler.rescheduleJob(tk, trigger);
        log.info("rescheduled task {} cron={}", taskId, cron);
      } else {
        var job = JobBuilder.newJob(ReportTriggerJob.class)
            .withIdentity(jk)
            .usingJobData("taskId", taskId)
            .storeDurably()
            .build();
        scheduler.scheduleJob(job, trigger);
        log.info("scheduled task {} cron={}", taskId, cron);
      }
    } catch (SchedulerException e) {
      throw new RuntimeException("schedule task " + taskId + " failed", e);
    }
  }

  @Override
  public void onOffline(Long taskId) {
    JobKey jk = jobKey(taskId);
    try {
      if (scheduler.checkExists(jk)) {
        scheduler.deleteJob(jk);
        log.info("deleted job task={}", taskId);
      }
    } catch (SchedulerException e) {
      throw new RuntimeException("delete job task " + taskId + " failed", e);
    }
  }

  @Override
  public void onCronChange(Long taskId, String cron) {
    onPublish(taskId, cron);
  }

  /**
   * 手动触发指定任务（以 manual=true 绕过 ONLINE 状态检查）。
   *
   * <p>仅限测试与运维补偿场景使用。调用方必须自行确认任务状态与触发合法性；
   * 不得经任何 Controller 直接暴露此能力。
   *
   * @param taskId 任务 ID
   */
  public void triggerNow(Long taskId) {
    JobKey jk = jobKey(taskId);
    try {
      JobDataMap data = new JobDataMap();
      data.put("manual", true);
      scheduler.triggerJob(jk, data);
      log.info("triggered now task={}", taskId);
    } catch (SchedulerException e) {
      throw new RuntimeException("trigger task " + taskId + " failed", e);
    }
  }
}
